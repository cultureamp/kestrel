# Docker file to deploy a CDK app
FROM node:10-alpine as nd
WORKDIR /usr/src/app

RUN apk -v --update add \
        python \
        py-pip \
        && \
    pip install --upgrade awscli && \
    apk -v --purge del py-pip && \
    rm /var/cache/apk/*

COPY package*.json ./
# Install dependencies first

RUN npm install --registry https://package-repository.continuous-integration.cultureamp.net/repository/npm-all/
COPY . .
RUN npm run build

ENTRYPOINT npm run cdk-deploy