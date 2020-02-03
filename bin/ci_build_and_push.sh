#!/bin/bash
set -e
set -o pipefail
set -u

if [[ "${BUILDKITE_BRANCH}" != "master" ]]; then
    REPO="226140413739.dkr.ecr.us-west-2.amazonaws.com/development/kotlin-cqrs-eventsourcing"
else
    REPO="226140413739.dkr.ecr.us-west-2.amazonaws.com/master/kotlin-cqrs-eventsourcing"
fi

TAG=$BUILDKITE_COMMIT

echo "--- authenticating with ECR"
$(aws ecr get-login --no-include-email --region us-west-2)

echo "--- building $TAG"
docker build --tag "$REPO:$TAG" .

echo "--- pushing $REPO:$TAG"
docker push "$REPO:$TAG"

echo "--- pushing as $REPO:latest"
docker tag "$REPO:$TAG" "$REPO:latest"
docker push "$REPO:latest"
