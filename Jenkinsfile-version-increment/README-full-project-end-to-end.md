# Java Maven App — Complete CI/CD Pipeline on AWS EC2

A fully automated, self-healing CI/CD pipeline for a Java Maven (Spring Boot)
application. Every push to `main` is automatically versioned, built, tested,
containerized, pushed to Docker Hub, and deployed to a live AWS EC2 instance
via Docker Compose — with built-in loop-prevention so the pipeline's own
automated commits never trigger it again.

This README explains the project from the ground up: what it does, how every
piece fits together, why it was built this way, and every real bug that was
hit and fixed getting here.

---

## 1. What this project actually does, in plain terms

Picture a developer making a small code change and pushing it to GitHub.
From that single `git push`, the following happens **automatically, with no
further human action**:

1. The app's version number goes up by itself (e.g. `1.3.144` → `1.3.145`)
2. The code is compiled and its tests are run
3. A Docker image is built, tagged with the new version, and pushed to
   Docker Hub
4. A live AWS EC2 server pulls that exact new image and restarts the
   application with it — the running app is now the new version
5. The version bump itself gets committed back to GitHub, labeled so it
   doesn't restart this whole process again

That's a real, working Continuous Integration / Continuous Deployment
pipeline — the same basic shape used by production engineering teams,
built and debugged from scratch.

---

## 2. Full architecture

```
Developer
   │ git push
   ▼
GitHub repository (main branch)
   │ webhook
   ▼
┌─────────────────────────────────────────────┐
│                 Jenkins Pipeline              │
│                                               │
│  check commit message  →  [skip ci]?          │
│         │ no                                  │
│         ▼                                     │
│  increment version  (Maven)                   │
│         ▼                                     │
│  build app  (mvn clean package)               │
│         ▼                                     │
│  build image  →  docker build, login, push    │
│         ▼                                     │
│  deploy  →  scp compose + script, ssh + run   │
│         ▼                                     │
│  commit version update  →  push [skip ci]     │
│         │                                     │
└─────────┼─────────────────────────────────────┘
          │ re-fires webhook
          ▼
   (caught by check commit message on next run)

                        │
                        ▼
                  Docker Hub
         ighojohn/testing-app:version-buildnum
                        │
                        ▼
              AWS EC2 (99.79.70.217)
        docker-compose.yml + deploy.sh
                        │
                        ▼
         demo-app container (port 8080→80)
         postgres-db container (port 5432)
```

---

## 3. The pipeline, stage by stage

### Stage 0 — check commit message
```groovy
stage('check commit message') {
    steps {
        script {
            def commitMessage = sh(script: 'git log -1 --pretty=%B', returnStdout: true).trim()
            if (commitMessage.contains('[skip ci]')) {
                echo "Commit message contains [skip ci] — aborting build."
                currentBuild.result = 'NOT_BUILT'
                error("Skipping build due to [skip ci] marker.")
            }
        }
    }
}
```
Reads the exact commit message that triggered this build. If it contains
`[skip ci]`, the pipeline halts immediately with `NOT_BUILT`, before any real
work happens. This is what actually stops the loop — more on why this exists
in Section 5.

### Stage 1 — increment version
```groovy
sh '''
    mvn build-helper:parse-version versions:set -DnewVersion='${parsedVersion.majorVersion}.${parsedVersion.minorVersion}.${parsedVersion.nextIncrementalVersion}' versions:commit
'''
def matcher = readFile('pom.xml') =~ '<version>(.+)</version>'
def version = matcher[0][1]
env.IMAGE_NAME = "$version-$BUILD_NUMBER"
```
Uses Maven's `build-helper` plugin to parse the current version out of
`pom.xml`, then `versions:set` to bump the patch number automatically. The
new version is read back from the file and combined with the Jenkins build
number to form a unique image tag, e.g. `1.3.145-47`.

### Stage 2 — build app
```groovy
sh 'mvn clean package'
```
Standard Maven build: compiles the code, runs the test suite, and packages
a runnable jar.

### Stage 3 — build image
```groovy
withCredentials([usernamePassword(credentialsId: 'docker-hub-repo', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
    sh "docker build -t ighojohn/testing-app:${IMAGE_NAME} ."
    sh "echo $PASS | docker login -u $USER --password-stdin"
    sh "docker push ighojohn/testing-app:${IMAGE_NAME}"
    sh "docker image prune -f"
}
```
Builds a Docker image from the jar, logs in to Docker Hub using credentials
stored securely in Jenkins, pushes the image, and prunes unused local images
to keep the server's disk from filling up over time.

### Stage 4 — deploy
```groovy
sshagent(['ec2-server-key']) {
    sh "scp -o StrictHostKeyChecking=no docker-compose.yml ec2-user@99.79.70.217:/home/ec2-user/docker-compose.yml"
    sh "scp -o StrictHostKeyChecking=no deploy.sh ec2-user@99.79.70.217:/home/ec2-user/deploy.sh"
    sh "ssh -o StrictHostKeyChecking=no ec2-user@99.79.70.217 'chmod +x /home/ec2-user/deploy.sh && /home/ec2-user/deploy.sh ${IMAGE_NAME}'"
}
```
Copies the Compose file and the deploy script to the EC2 server fresh on
every run, then runs the script remotely over SSH — passing in the exact
image tag that was just built and pushed.

**`deploy.sh`:**
```bash
#!/bin/bash
set -e
IMAGE_TAG=$1
export IMAGE_TAG
docker-compose -f /home/ec2-user/docker-compose.yml pull
docker-compose -f /home/ec2-user/docker-compose.yml up -d
```
Pulls the newly pushed image and brings the service up. Docker Compose
handles replacing the existing running container automatically — no manual
`stop`/`rm` bookkeeping required.

### Stage 5 — commit version update
```groovy
withCredentials([usernamePassword(credentialsId: 'github-credentials', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
    sh 'git config --global user.email "jenkins@example.com"'
    sh 'git config --global user.name "jenkins"'
    sh "git remote set-url origin https://${USER}:${PASS}@github.com/igho-john/build-java-maven-app-with-jenkin-.git"
    sh 'git add .'
    sh 'git commit -m "ci: version bump [skip ci]"'
    sh 'git push origin HEAD:main'
}
```
Commits the version bump in `pom.xml` back to the repository, with
`[skip ci]` in the message — the exact marker Stage 0 checks for.

---

## 4. Supporting files

**`docker-compose.yml`** (repo root):
```yaml
services:
  demo-app:
    image: ighojohn/testing-app:${IMAGE_TAG}
    container_name: demo-app
    ports:
      - "8080:80"
  postgres-db:
    image: postgres:16
    container_name: postgres-db
    environment:
      - POSTGRES_PASSWORD=my_pwd
    ports:
      - "5432:5432"
```

**`deploy.sh`** (repo root) — shown in full above under Stage 4.

**Jenkins credentials required:**
| ID | Type | Used for |
|---|---|---|
| `docker-hub-repo` | Username/password | Docker login + push |
| `github-credentials` | Username/password (PAT) | Pushing the version-bump commit |
| `ec2-server-key` | SSH private key | SSH/SCP to the EC2 deploy target |

---

## 5. Why it's built this way — the real design decisions

**Why `[skip ci]` needs an actual check, not just a label:** early in building
this, `[skip ci]` was added to the commit message without anything reading
it. The GitHub webhook doesn't know or care what the message says — it fires
on every push regardless. Without Stage 0 actively checking for that marker
and halting, the pipeline's own final commit re-triggers itself, which
re-triggers it again, forever.

**Why the image tag is dynamic (`version-buildnumber`), never hardcoded:** a
hardcoded tag means every deploy ships the same image forever, defeating the
purpose of automatic versioning. Passing `${IMAGE_NAME}` through from the
build stage all the way to `deploy.sh`'s `$1` argument guarantees the server
always runs exactly what was just built.

**Why Docker Compose instead of a raw `docker run`:** manually tracking
container names and chaining `stop && rm && run` is fragile — any container
started without an explicit name makes the next deploy's cleanup step fail
to find it, causing port collisions. Compose tracks the service by name
internally and handles replacement cleanly via `up -d`.

**Why the deploy logic lives in `deploy.sh`, not inline in the Jenkinsfile:**
keeps the actual remote commands in one real, version-controlled, testable
file — and makes it possible to run the exact same deploy by hand on the
server for debugging, without going through Jenkins at all.

---

## 6. Real problems hit and fixed, building this

This is the part that actually matters most for understanding how this
pipeline came to look the way it does — every one of these was a genuine
failure, diagnosed from a real error message, not anticipated in advance.

| Problem | Root cause | Fix |
|---|---|---|
| Infinite build loop | Pipeline's own commit re-triggered the webhook with nothing checking for it | Added Stage 0 commit-message check |
| Disk filled to 99% | 45+ unused Docker images from the loop above | `docker system prune` + `docker image prune -f` after every push |
| Jenkins node went offline | Low disk space threshold tripped by the above | Resized the EC2 EBS volume (`growpart` + `resize2fs`) |
| `Bad substitution` error | A dot inside `${...}` isn't valid bash syntax outside quotes | Wrapped the Maven version expression in real single quotes |
| Docker permission denied | Host and container `docker` group had different numeric GIDs | `groupmod -g <hostGID> docker` inside the container |
| `NoSuchMethodError: buildJar` | Missing `@Library(...)` declaration at the top of the Jenkinsfile | Added the library declaration back |
| `Could not find any definition of libraries` | The Shared Library was never registered under Jenkins' global config | Registered it under Manage Jenkins → System → Global Pipeline Libraries |
| `error in libcrypto` loading SSH key | Corrupted/incompletely pasted private key credential | Regenerated the key pair, re-pasted the full key including BEGIN/END lines |
| `port is already allocated` | Container had no fixed name, so cleanup on redeploy found nothing to stop | Added `--name`, later replaced entirely by switching to Docker Compose |
| `scp: No such file or directory` (×2) | `docker-compose.yml` vs `.yaml` filename mismatches, twice | Standardized on `docker-compose.yml` everywhere it's referenced |
| `manifest unknown` on pull | Compose file referenced `demo-app`, an image that was never built or pushed | Corrected to the actual pushed image, `testing-app` |
| `[skip ci]` present but loop returned anyway | An intermediate edit had dropped the Stage 0 check while the commit still said `[skip ci]` | Confirmed the check stage exists in every subsequent version |

---

## 7. How to run this yourself

1. Fork this repository
2. Create a Jenkins Pipeline job pointing at your fork, using
   `Jenkinsfile-version-increment/Jenkinsfile`
3. Add the three credentials listed in Section 4
4. Confirm `docker-compose.yml` and `deploy.sh` exist at the repo root, with
   the image name matching what Jenkins actually builds and pushes
5. Set up a GitHub webhook pointing at
   `http://<your-jenkins-ip>:8080/github-webhook/`
6. Push a commit — the full pipeline should run end-to-end automatically

---

## 8. What's next

- Add a health check after deploy that confirms the app actually responds
  before the build is marked successful
- Add a rollback step that redeploys the previous image tag if that health
  check fails
- Move from a single EC2 instance to Kubernetes for real orchestration
- Provision the EC2 instance itself with Terraform, rather than deploying to
  one created manually
- Add Prometheus/Grafana monitoring for the deployed application
