import cdk = require('@aws-cdk/core');
import ssm = require('@aws-cdk/aws-ssm');
import s3 = require('@aws-cdk/aws-s3');
import {Schedule} from '@aws-cdk/aws-applicationautoscaling';
import {Containers, FargateService, AutoScalingStrategy} from '@cultureamp/cdk-services';
import {
    Camp,
    ConfigHandler,
    DataClassification,
    Stack,
    StackProps,
    Workload,
    workloadForEnvironment
} from '@cultureamp/cdk-common';
import {S3Statements} from '@cultureamp/cdk-iam/lib/statements/s3-statements';
import {ScheduledFargateTask} from '@cultureamp/cdk-tasks';

const app = new cdk.App();

const projectName = process.env.BUILDKITE_PIPELINE_SLUG as string || "kotlin-cqrs-eventsourcing";
const commit = process.env.BUILDKITE_COMMIT as string;
const branch = process.env.BUILDKITE_BRANCH as string;
const buildNumber = process.env.BUILDKITE_BUILD_NUMBER as string;
const farm = process.env.FARM as string;

const config = new ConfigHandler({farm});
const crsFarm = config.get('crsFarm') || farm;
const environment = config.get('environment') || 'development';
const crsParamsStorePrefix = `a-and-u/comments-reporting-service`;

const crsParameter = (suffix: string): string => `parameter/${crsParamsStorePrefix}/${crsFarm}/${suffix}`;

const tags = {
    asset: projectName,
    camp: Camp.UNDERSTAND,
    dataClassification: DataClassification.INTERNAL_USE_ONLY,
    environment: environment,
    team: 'comments',
    workload: workloadForEnvironment(environment) || Workload.DEVELOPMENT,
};

class ServiceStack extends Stack {
    constructor(parent: cdk.App, id: string, props: StackProps) {
        super(parent, id, props);

        const stackName = cdk.Stack.of(this).stackName;
        const sourceSecurityGroupId = cdk.Fn.importValue('cdk-addis-source-security-groups:KotlinCqrsEventsourcing');
        const crsSecurityGroup = cdk.Fn.importValue('cdk-addis-source-security-groups:CommentsReportingService');
        const dbSecurityGroupId = cdk.Fn.importValue('a-and-u:database-security-group');
        const ecsContainerImage = Containers.getEcrImage(this, projectName, branch, commit);
        const clusterName = 'kotlin-cqrs-eventsourcing';
        const splunkToken = ssm.StringParameter.valueForStringParameter(this, '/common/understand/SPLUNK_HEC_TOKEN');
        const splunkIndex = ssm.StringParameter.valueForStringParameter(this, '/common/understand/SPLUNK_INDEX');

        const snapshotBucket = new s3.Bucket(this, `${projectName}-snapshots-bucket`, {
            bucketName: `${stackName}-snapshots`,
            removalPolicy: cdk.RemovalPolicy.DESTROY,
            accessControl: s3.BucketAccessControl.PRIVATE,
            encryption: s3.BucketEncryption.KMS_MANAGED,
            blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL
        });

        const commonContainerEnvironmentVars = {
            "BUGSNAG_RELEASE_STAGE": farm,
            "BUGSNAG_APP_VERSION": buildNumber,
            "S3_BUCKET_NAME": snapshotBucket.bucketName
        };

        let commonContainerSecretEnvironmentVars: {[k: string]: any} = {
            "NEW_RELIC_LICENSE_KEY": "parameter/common/NEW_RELIC_LICENSE_KEY",
            "BUGSNAG_API_KEY": `secret:/${projectName}/BUGSNAG_API_KEY`
        };

        if (config.get('newCRSStack')) {
            commonContainerSecretEnvironmentVars = {
                ...commonContainerSecretEnvironmentVars,
                "DB_CREDENTIALS": `secret:/comments-reporting-service-${farm}/rds/MasterCredentials`
            };
        } else {
            commonContainerSecretEnvironmentVars = {
                ...commonContainerSecretEnvironmentVars,
                "DB_HOST": crsParameter("DB_HOST"),
                "DB_USERNAME": crsParameter("DB_USERNAME"),
                "DB_PASSWORD": crsParameter("DB_PASSWORD")
            };
        }

        if (config.get('hasDBPrefix')) {
            commonContainerSecretEnvironmentVars = {
                ...commonContainerSecretEnvironmentVars,
                "DB_PREFIX": crsParameter("DB_PREFIX")
            };
        }

        const apiService = new FargateService(this, `${projectName}-api`, {
            ecsContainerImage: ecsContainerImage,
            serviceType: 'api',
            clusterName: clusterName,
            serviceSourceSecurityGroupIds: [sourceSecurityGroupId, dbSecurityGroupId],
            containerEnvironmentVars: commonContainerEnvironmentVars,
            containerSecretEnvironmentVars: commonContainerSecretEnvironmentVars,
            containerPort: 5005,
            healthCheckGracePeriod: cdk.Duration.minutes(5),
            cpu: config.get('cpu'),
            memoryMiB: config.get('memory'),
            desiredCount: config.get('desiredCount'),
            // When deploying a new serialization version, api deployments will fail until a snapshot is created. This
            // means the service might be in the process of deploying for extended periods of time. Setting this to 100%
            // ensures that API capacity is not reduced for that time.
            minimumHealthyPercent: 100,
            autoScalingStrategy: new AutoScalingStrategy.CpuUtilization({
                minCapacity: config.get('minCount') || 1
            })
        });

        apiService.addStatementToTaskRolePolicy(S3Statements.getObjects(snapshotBucket.bucketName));
        apiService.addStatementToTaskRolePolicy(S3Statements.listBuckets());

        apiService.addLoadBalancerToService(
            {
                healthCheck: {path: '/v1/ready'},
                publicLoadBalancer: false,
                useHttps: true,
                sourceSecurityGroups: [sourceSecurityGroupId, crsSecurityGroup],
                targetDeregistrationDelay: cdk.Duration.seconds(10)
            }
        );

        apiService.addSplunkForwarder({
            splunkToken: splunkToken,
            splunkIndex: splunkIndex
        });

        // Set a cron rule to run scheduled task every 24 hours, starting from 5 minutes after generation of changeset
        const firstRunDelay = 5; // minutes
        let hours = new Date().getUTCHours();
        let minutes = new Date().getUTCMinutes() + firstRunDelay;
        if (minutes >= 60) {
            minutes -= 60;
            hours = (hours + 1) % 24;
        }
        const schedule = Schedule.cron({hour: hours.toString(), minute: minutes.toString()});

        const scheduledTask = new ScheduledFargateTask(this, `${projectName}-snapshotter`, {
            ecsContainerImage: ecsContainerImage,
            clusterName: clusterName,
            taskSourceSecurityGroupIds: [sourceSecurityGroupId, dbSecurityGroupId],
            containerEnvironmentVars: {...commonContainerEnvironmentVars, "RUN_MODE": "CREATE_SNAPSHOTS"},
            containerSecretEnvironmentVars: commonContainerSecretEnvironmentVars,
            cpu: config.get('snapshotterCpu') || config.get('cpu'),
            memoryMiB: config.get('snapshotterMemory') || config.get('memory'),
            schedule: schedule
        });

        scheduledTask.addSplunkForwarder({
            splunkToken: splunkToken,
            splunkIndex: splunkIndex
        });

        scheduledTask.addStatementToTaskRolePolicy(S3Statements.getObjects(snapshotBucket.bucketName));
        scheduledTask.addStatementToTaskRolePolicy(S3Statements.putObjects(snapshotBucket.bucketName));
        scheduledTask.addStatementToTaskRolePolicy(S3Statements.listBuckets());
    }
}

new ServiceStack(app, `${projectName}-${farm}`, {
    tags: tags,
    permissionBoundaryArn: `arn:aws:iam::${cdk.Aws.ACCOUNT_ID}:policy/kotlin-cqrs-eventsourcing-service-permission-boundary`
});

app.synth();
