# Jenkins CI/CD Pipeline — Git Pull & Maven Build on AWS EC2

A hands-on DevOps project where I set up Jenkins on an AWS EC2 instance, integrated the Git plugin to pull source code from GitHub, and configured a declarative pipeline to build a Java project using Maven.

---

## 🧱 Architecture

```
GitHub Repo (onlinebookstore)
        │
        │  git clone (main branch)
        ▼
   Jenkins Pipeline Job (git-pull-job)
        │
        ├── Stage: CODE_PULL   → pulls source from GitHub
        └── Stage: CODE_BUILD  → runs `mvn clean package`
        │
        ▼
   AWS EC2 Instance (ap-south-1 / Mumbai)
   - Jenkins installed & running on port 8080
   - Git plugin installed
   - Maven installed on host
```

---

## 🛠️ Tech Stack

| Component      | Details                              |
|-----------------|---------------------------------------|
| Cloud Provider  | AWS EC2 (ap-south-1 — Mumbai region)  |
| CI/CD Tool      | Jenkins                               |
| SCM             | Git / GitHub                          |
| Build Tool      | Apache Maven                          |
| Pipeline Type   | Declarative Pipeline (Jenkinsfile-style script) |
| Source Repo     | `mayurmwagh/onlinebookstore` (test project) |

---

## 🚀 Step-by-Step Setup

### 1. Launch EC2 Instance
- Launched an EC2 instance in the **ap-south-1 (Mumbai)** region.
- Opened inbound port **8080** in the security group so Jenkins' web UI is reachable at `http://<EC2-Public-IP>:8080`.

### 2. Install Java, Jenkins & Maven on the EC2 Host
```bash
# Update packages
sudo apt update -y

# Install Java (required by Jenkins)
sudo apt install openjdk-17-jdk -y
java -version

# Add Jenkins repo & install Jenkins
sudo wget -O /usr/share/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key
echo "deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc]" \
  https://pkg.jenkins.io/debian-stable binary/ | sudo tee \
  /etc/apt/sources.list.d/jenkins.list > /dev/null
sudo apt update -y
sudo apt install jenkins -y

# Start Jenkins
sudo systemctl enable jenkins
sudo systemctl start jenkins
sudo systemctl status jenkins

# Install Maven on the host (used by the pipeline's build stage)
sudo apt install maven -y
mvn -version
```

### 3. Unlock Jenkins & Initial Setup
- Retrieved the initial admin password:
  ```bash
  sudo cat /var/lib/jenkins/secrets/initialAdminPassword
  ```
- Opened `http://<EC2-Public-IP>:8080` in the browser and completed the setup wizard.

### 4. Install the Git Plugin
- Navigated to **Manage Jenkins → Plugins**.
- Installed the **Git Plugin** so Jenkins pipelines can natively use the `git` step to clone repositories.
- Confirmed via **Manage Jenkins** that Jenkins was reachable and fully configured at `13.235.84.108:8080`.

### 5. Create a New Pipeline Job
- From the Jenkins dashboard: **New Item → Enter name `git-pull-job` → select Pipeline → OK**.

### 6. Configure the Pipeline Script
Went to **git-pull-job → Configure → Pipeline** and used a **Pipeline script** (declarative syntax) directly in the job:

```groovy
pipeline {
    agent any

    stages {
        stage('CODE_PULL') {
            steps {
                git branch: 'master',
                    url: 'https://github.com/mayurmwagh/onlinebookstore.git'
            }
        }
        stage('CODE_BUILD') {
            steps {
                sh 'mvn clean package'
            }
        }
    }
}
```

- **CODE_PULL** stage: clones the `master` branch of the `onlinebookstore` repo from GitHub using the Git plugin's `git` step.
- **CODE_BUILD** stage: runs `mvn clean package` on the EC2 host to compile and package the Java project.
- Left **Use Groovy Sandbox** checked for safe script execution.

### 7. Run the Build
- Triggered builds via **Build Now**.
- First few runs (**#1 – #4**) failed while I was troubleshooting the pipeline/environment.
- Build **#5** completed **successfully** ✅, confirming that:
  - The Git plugin correctly pulled code from GitHub.
  - Maven was correctly installed and accessible in the Jenkins execution environment.
  - The `mvn clean package` step built the project without errors.

---

## 🐞 Issues Faced & Fixes

| Issue | Root Cause | Fix |
|-------|-----------|-----|
| Early builds (#1–#4) failing | Environment/config issues while setting up Git + Maven paths on the pipeline agent | Verified Maven installation on host, confirmed Git plugin was installed, corrected pipeline syntax, re-ran build |
| Needed to confirm Maven availability to Jenkins | Jenkins executor may run as a different user/environment than the interactive shell | Verified `mvn -version` works from the same shell context Jenkins uses (`agent any`), and confirmed Maven binary is on the system `PATH` |

---

## 📌 What This Demonstrates

- Setting up **Jenkins from scratch** on a cloud (AWS EC2) host.
- Installing and configuring the **Git plugin** for SCM integration.
- Writing a **declarative Jenkins pipeline** with multiple stages.
- Integrating **Maven** as a build tool inside a CI pipeline.
- Debugging real pipeline failures using Jenkins build history and logs.

---

## 🔮 Next Steps

- [ ] Move pipeline script into a `Jenkinsfile` checked into the repo (Pipeline-from-SCM) instead of storing it directly in the job config.
- [ ] Add a **SonarQube** stage for static code analysis.
- [ ] Add build artifact archiving (`archiveArtifacts`) for the packaged `.jar`/`.war`.
- [ ] Add Slack/email notifications on build success/failure.
- [ ] Parameterize the branch/repo URL for reuse across projects.

---

## 🔗 Related Links

- GitHub: [github.com/deepbijwe](https://github.com/deepbijwe)
- Portfolio: [deepbijwe.in](https://deepbijwe.in)