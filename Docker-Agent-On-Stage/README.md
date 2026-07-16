# Running Jenkins Pipeline Stages Inside a Docker Agent (EC2)

## Overview

This project extends an existing Jenkins CI/CD setup (running on an AWS EC2 Ubuntu instance) to support **Docker-based build agents** in a declarative pipeline. Instead of installing Maven/JDK directly on the Jenkins host, the `Build` stage now runs inside a `maven:3.9.11-eclipse-temurin-17` container, spun up and torn down automatically by Jenkins for each build.

```
pipeline {
    agent none
    stages {
        stage('Checkout') {
            agent any
            steps {
                git branch: 'master',
                    url: 'https://github.com/mayurmwagh/onlinebookstore.git'
            }
        }
        stage('Build') {
            agent {
                docker {
                    image 'maven:3.9.11-eclipse-temurin-17'
                    reuseNode true
                }
            }
            steps {
                sh 'mvn clean package'
            }
        }
    }
}
```

Getting this to work required setting up Docker on the Jenkins server itself and installing the correct Jenkins plugin — both of which surfaced errors along the way.

---

## Step 1 — Discover Docker isn't installed on the Jenkins EC2 host

Connected to the Jenkins EC2 instance (`i-0d4a2fad31a1aee18`, Jenkins-Server) via EC2 Instance Connect and checked for Docker:

```bash
root@ip-172-31-28-243:~# docker ps
Command 'docker' not found, but can be installed with:
snap install docker        # version 29.3.1, or
apt install docker.io      # version 29.1.3-0ubuntu4.1
apt install podman-docker  # version 5.7.0+ds2-3build1
```

**❌ Error:** `docker: command not found`
**Cause:** Docker was never installed on this instance — the server had only been set up for Git + Maven builds so far, not container-based agents.

---

## Step 2 — Install Docker

```bash
root@ip-172-31-28-243:~# apt update && apt install docker.io -y
```

This pulled in Docker and its dependencies (`containerd`, `runc`, `bridge-utils`, etc.) and completed successfully.

---

## Step 3 — Give the `jenkins` user permission to run Docker

By default, only `root` (and members of the `docker` group) can talk to the Docker daemon. Since Jenkins runs pipeline steps as the `jenkins` system user, it needed to be added to that group:

```bash
root@ip-172-31-28-243:/etc# usermod -aG docker jenkins
root@ip-172-31-28-243:/etc# cat group
...
docker:x:110:jenkins
```

Confirmed `jenkins` now shows up under the `docker` group entry.

**Extra troubleshooting step taken:** ownership on `/etc/docker/` was also changed to the `jenkins` user:

```bash
root@ip-172-31-28-243:/etc# chown jenkins:jenkins /etc/docker/
root@ip-172-31-28-243:/etc# ls -ltd docker/
drwxr-xr-x 2 jenkins jenkins 4096 Apr 29 16:40 docker/
```

> ⚠️ **Note for future-you:** normally, adding `jenkins` to the `docker` group is enough — Docker access goes through the `docker.sock` socket, not through file ownership of `/etc/docker/`. Chowning `/etc/docker` to a non-root user isn't the standard fix and can cause confusing permission issues later (that directory usually needs to stay root-owned, since the Docker daemon itself runs as root and writes config there). If a permission-denied error resurfaces, check `ls -l /var/run/docker.sock` first and confirm the `jenkins` user's group membership actually applied (a fresh login or `newgrp docker` / service restart is required for group changes to take effect) before changing ownership of daemon config directories again.

---

## Step 4 — Install the Docker Pipeline plugin in Jenkins

The `agent { docker { ... } }` block in a Jenkinsfile is **not built into Jenkins core** — it's provided by the **Docker Pipeline** plugin. Without it, the pipeline would fail to parse that agent block.

Installed via **Manage Jenkins → Plugins → Available plugins**, searched `docker`, selected **Docker Pipeline**:

- Provides `docker { image ... }` agent syntax and `docker.build()` / `docker.image().inside()` pipeline steps.
- (Docker Commons plugin was auto-included as a dependency, since Docker Pipeline relies on it.)

## Step 5 — Restart Jenkins to load the plugin

Triggered a Safe Restart after installation so the new plugin was fully loaded before running a build:

> "Jenkins is restarting... Safe Restart — Builds on agents can usually continue."

---

## Step 6 — Run the pipeline

Triggered the `Stage-Agent-pipeline` job. Console output showed:

- Maven resolved and downloaded dependencies (e.g. `webapp-runner-8.0.30.2.jar`) inside the container.
- `BUILD SUCCESS` from Maven.
- Jenkins then cleanly tore down the container it had created for the stage:
  ```
  $ docker stop --time=1 cdf5726dfd77...
  $ docker rm -f --volumes cdf5726dfd77...
  ```
- Pipeline finished with `Finished: SUCCESS`.

This confirms `reuseNode true` worked as expected — the code checked out in the `Checkout` stage was available to Maven inside the container, and Jenkins handled container lifecycle (create → run steps → stop → remove) automatically.

---

## Errors & Troubleshooting Summary

| # | Symptom | Root Cause | Fix |
|---|---------|------------|-----|
| 1 | `docker: command not found` on `docker ps` / `docker ps -a` | Docker was never installed on the Jenkins EC2 host | `apt update && apt install docker.io -y` |
| 2 | Jenkins (running as `jenkins` user) can't run Docker commands | `jenkins` user not in the `docker` group | `usermod -aG docker jenkins` |
| 3 | (Precautionary) potential permission issues on `/etc/docker` | Ownership defaulted to `root` | `chown jenkins:jenkins /etc/docker/` — ⚠️ non-standard fix, revisit if daemon-level issues appear later |
| 4 | Pipeline `agent { docker {...} }` block wouldn't be recognized | Docker Pipeline plugin not installed | Installed **Docker Pipeline** plugin via Manage Jenkins → Plugins |
| 5 | Plugin not taking effect immediately | Jenkins needed to reload plugin classes | Performed a Safe Restart |

---

## Key Learnings

- A declarative pipeline's `agent { docker { image ... } }` block requires **two separate things** to be true: (1) Docker installed and usable by the Jenkins process on the underlying node, and (2) the **Docker Pipeline** plugin installed in Jenkins.
- `reuseNode true` is essential when a `Checkout` stage (running on `agent any`) and a `Build` stage (running in a Docker container) need to share the same workspace/files.
- Adding a user to the `docker` group only takes effect for **new** shell sessions / processes — worth remembering if `docker` commands still fail with permission errors right after `usermod`.
- Jenkins automatically manages the full container lifecycle (start → exec steps → stop → remove) for Docker agents — no manual cleanup needed.