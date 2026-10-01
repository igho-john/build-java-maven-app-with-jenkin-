#!/bin/bash
set -e

IMAGE_TAG=$1

echo "Deploying with IMAGE_TAG=${IMAGE_TAG}..."

export IMAGE_TAG

docker-compose -f /home/ec2-user/docker-compose.yml pull
docker-compose -f /home/ec2-user/docker-compose.yml up -d

echo "Deploy complete."
