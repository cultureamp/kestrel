#!/bin/bash
set -eu

dev_farms=(ibex)

steps=$(echo "steps:"

for FARM in "${dev_farms[@]}" 
do
    option=$(buildkite-agent meta-data get "deploy-${FARM}")
    if [ ${option:=skip} == 'deploy' ]; then
        echo "  - name: '$FARM Deployment'"
        echo "    command: 'bin/ci_cdk_deploy'"
        echo "    env:"
        echo "      FARM: $FARM"
        echo "    concurrency: 1"
        echo "    concurrency_group: ${BUILDKITE_PIPELINE_SLUG}/${FARM}/deploy"
        echo "    agents:"
        echo "      queue: build-unrestricted"
        echo "    plugins:"
        echo "      cultureamp/aws-assume-role:"
        echo "        role: arn:aws:iam::527100417633:role/deploy-role-kotlin-cqrs-eventsourcing"
    fi
done)

if [[ "${steps}" == "steps:" ]]; then
    echo "No steps to upload"
else
    echo "$steps" | buildkite-agent pipeline upload
fi
