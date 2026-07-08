# Jenkins CI/CD Server Setup on AWS EC2

Provisioning a self-managed Jenkins server on an AWS EC2 instance — from launching the VM to unlocking Jenkins and configuring the first admin user.

## Overview

This project documents deploying **Jenkins 2.555.3** on an **Ubuntu 26.04** EC2 instance in the `ap-south-1` (Mumbai) region, running on OpenJDK 21, and completing the full setup wizard through to a working dashboard.

**Stack:** AWS EC2, Ubuntu 26.04, OpenJDK 21, Jenkins 2.555.3

---

## 1. Launch the EC2 Instance

Launched a new EC2 instance from the AWS Console with the following configuration:

| Setting | Value |
|---|---|
| Name | `jenkins-server` |
| AMI | Canonical, Ubuntu 26.04, amd64 (`ami-01a00762f46d584a1`) |
| Instance type | `c7i-flex.large` (2 vCPU, 4 GiB memory) |
| Key pair | `my-mumbai` |
| Storage | 1 volume — 8 GiB |
| Region | Asia Pacific (Mumbai) — `ap-south-1` |

## 2. Configure the Security Group

Created a new security group with two inbound rules:

| Type | Protocol | Port | Source | Description |
|---|---|---|---|---|
| SSH | TCP | 22 | 0.0.0.0/0 | Remote access |
| Custom TCP | TCP | 8080 | 0.0.0.0/0 | Jenkins access |

> ⚠️ Opening `0.0.0.0/0` is fine for a lab/demo environment, but for production this should be restricted to known IP ranges.

Launched the instance and confirmed it reached the **Running** state.

- **Instance ID:** `i-0d4a2fad31a1aee18`
- **Public IP:** `13.235.84.108`
- **Private IP:** `172.31.28.243`

## 3. Connect and Install Java

SSH'd into the instance and switched to root:

```bash
sudo -i
```

Installed `fontconfig` and OpenJDK 21 (Jenkins' required runtime), then verified the install:

```bash
apt update && apt install fontconfig openjdk-21-jre
java -version
```

```
openjdk version "21.0.11" 2026-04-21
OpenJDK Runtime Environment (build 21.0.11+10-1-26.04.2-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.11+10-1-26.04.2-Ubuntu, mixed mode, sharing)
```

## 4. Add the Jenkins Repository and Install

Added the Jenkins signing key and stable Debian repo, then installed Jenkins:

```bash
sudo wget -O /etc/apt/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key

echo "deb [signed-by=/etc/apt/keyrings/jenkins-keyring.asc]" \
  https://pkg.jenkins.io/debian-stable binary/ | sudo tee \
  /etc/apt/sources.list.d/jenkins.list > /dev/null

sudo apt update
sudo apt install jenkins
```

Jenkins and its `net-tools` dependency installed cleanly (~99.6 MB download).

## 5. Verify the Jenkins Service

Checked that Jenkins was up and running on port 8080:

```bash
systemctl status jenkins
```

```
● jenkins.service - Jenkins Continuous Integration Server
     Loaded: loaded (/usr/lib/systemd/system/jenkins.service; enabled)
     Active: active (running)
   Main PID: 4063 (java)
     CGroup: /usr/bin/java ... --webroot=/var/cache/jenkins/war --httpPort=8080
```

Jenkins version installed: **2.555.3**

## 6. Retrieve the Initial Admin Password

```bash
cd /var/lib/jenkins/secrets/
cat initialAdminPassword
```

Copied the generated password for use in the browser-based setup wizard.

## 7. Unlock Jenkins

Navigated to `http://13.235.84.108:8080` in the browser, which loaded the **Unlock Jenkins** screen, and pasted in the password retrieved from `/var/lib/jenkins/secrets/initialAdminPassword`.

## 8. Customize Jenkins — Install Suggested Plugins

Selected **Install suggested plugins**. Jenkins installed the community-recommended plugin set, including:

- Folders, Git, Pipeline, GitHub Branch Source
- Credentials Binding, Matrix Authorization Strategy, LDAP
- Timestamper, Workspace Cleanup, Build Timeout
- Ant, Gradle, SSH Build Agents, Email Extension, Mailer
- OWASP Markup Formatter, Pipeline Graph View, Dark Theme

## 9. Create the First Admin User

Filled out the **Create First Admin User** form:

| Field | Value |
|---|---|
| Username | `Deep-bijwe` |
| Full name | `train-with-deep` |
| Password | *(set)* |
| Email | *(set)* |

## 10. Configure the Jenkins URL

On the **Instance Configuration** step, confirmed the root Jenkins URL:

```
http://13.235.84.108:8080/
```

Clicked **Save and Finish** to complete the wizard.

## 11. Jenkins Dashboard

Setup complete — landed on the Jenkins dashboard ("Welcome to Jenkins!") with:

- Build Queue — empty
- Build Executor Status — `0/2`
- Options to **Create a job**, **Set up an agent**, or **Configure a cloud**

Jenkins is now live and ready for pipeline/job configuration on `jenkins-server` (EC2, `ap-south-1`).

---

## Next Steps

- [ ] Configure a Jenkins Pipeline job connected to a GitHub repo
- [ ] Add credentials for GitHub/DockerHub integration
- [ ] Restrict the security group's SSH/8080 access to specific IPs
- [ ] Set up an Elastic IP so the Jenkins URL survives instance restarts
- [ ] Explore executor scaling / distributed build agents

## Tech Stack

`AWS EC2` · `Ubuntu 26.04` · `OpenJDK 21` · `Jenkins 2.555.3` · `Linux`