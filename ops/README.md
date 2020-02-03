## Infrastructure
This directory contains a CDK App that you can use to deploy all of your infrastructure for your microservice. The [Cloud Development Kit (CDK)](https://docs.aws.amazon.com/CDK/latest/userguide/what-is.html) is a software development framework, built by AWS, for defining cloud infrastructure in code and provisioning it through AWS CloudFormation. Culture Amp maintains a [library](https://github.com/cultureamp/cdk-constructs) of reusable constructs that can be used to easily build your infrastructure following our sensible defaults. Your Buildkite pipeline is configured to run any updates you make to this App in the respective environments.

This is the main stack that will create all the resources to run your microservice. This stack contains a Fargate Service that you will need to configure.

#### Configuration Required
- [ ] Update tags in `index.ts`
 ```typescript
 const tags = {
    asset: projectName,
    camp: Camp.AMPLIFY,
    dataClassification: DataClassification.INTERNAL_USE,
    environment: environment,
    team: 'sre',
    workload: workloadForEnvironment(environment) || Workload.DEVELOPMENT,
 }
 ```
- [ ] Review Buildkite pipeline [steps](../.buildkite/pipeline.yaml)

##### Useful Constructs
- [CDK Common](https://github.com/cultureamp/cdk-constructs/tree/master/packages/cdk-common)
- [CDK Data](https://github.com/cultureamp/cdk-constructs/tree/master/packages/cdk-data)
- [CDK Services](https://github.com/cultureamp/cdk-constructs/tree/master/packages/cdk-services)
- [CDK Logging](https://github.com/cultureamp/cdk-constructs/tree/master/packages/cdk-logging)