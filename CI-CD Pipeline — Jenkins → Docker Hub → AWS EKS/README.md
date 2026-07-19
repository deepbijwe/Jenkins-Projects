# Node.js CI/CD Pipeline — Jenkins → Docker Hub → AWS EKS

This project builds, tests, and deploys a Node.js application using a Jenkins pipeline that
pushes a Docker image to Docker Hub and deploys it to an EKS cluster running in a **separate
AWS account**, exposed to the internet via a Kubernetes `LoadBalancer` Service.

## Architecture

| | Account A | Account B |
|---|---|---|
| **Hosts** | Jenkins server (EC2) | EKS cluster + worker nodes |
| **Role** | Runs the CI/CD pipeline: checkout, test, build, push image | Runs the deployed application, exposed via LoadBalancer |
| **Key resource** | `New-jenkins-server` EC2 instance | `demo-ekscluster` |

Because the cluster lives in a different AWS account than Jenkins, the pipeline authenticates
to Account B using an IAM access key/secret stored as Jenkins credentials — **not** an instance
role, since the Jenkins EC2 role belongs to Account A.

---

## 1. Provision the Jenkins server (Account A)

1. Launch an EC2 instance (e.g. `c7i-flex.large`, Ubuntu) in Account A.
2. SSH in and switch to root:
   ```bash
   sudo -i
   ```
3. Update packages:
   ```bash
   apt update
   ```

## 2. Install Java + Jenkins

```bash
apt install openjdk-17-jre -y

curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key | tee \
  /usr/share/keyrings/jenkins-keyring.asc > /dev/null

echo deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] \
  https://pkg.jenkins.io/debian-stable binary/ | tee \
  /etc/apt/sources.list.d/jenkins.list > /dev/null

apt update
apt install jenkins -y
systemctl enable jenkins
systemctl start jenkins
```

Jenkins is now reachable at `http://<ec2-public-ip>:8080` (unlock using
`/var/lib/jenkins/secrets/initialAdminPassword`).

## 3. Install Docker

```bash
apt update && apt install docker.io -y
systemctl enable docker
systemctl start docker
```

**Note on the `jenkins` user and the `docker` group:** adding `jenkins` to the `docker` group
(`usermod -aG docker jenkins`) is a common convenience so pipeline shell steps can run `docker`
without `sudo`. It is **not mandatory** — an `agent { docker { image ... } }` pipeline can also
work without it — but if you rely on plain `sh 'docker build ...'` steps like this pipeline does,
the jenkins user needs docker group membership (see Troubleshooting §1).

## 4. Install Node.js

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt install -y nodejs
node -v
npm -v
```

## 5. Install AWS CLI

```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
apt install -y unzip
unzip awscliv2.zip
./aws/install
aws --version
```

## 6. Install kubectl

```bash
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl
mv kubectl /usr/local/bin/
kubectl version --client
```

At this point `node -v`, `npm -v`, and `docker --version` should all resolve — this is exactly
what the pipeline's **Verify Environment** stage checks on every run.

---

## 7. Set up the EKS cluster (Account B)

1. In Account B, create an EKS cluster (e.g. `demo-ekscluster`) with at least one worker node
   group.
2. Confirm the cluster and nodes are `Running`/`Ready` via the EKS console or:
   ```bash
   kubectl get nodes
   ```

Since Jenkins (Account A) needs to manage this cluster (Account B), the IAM principal behind
the `AWS_Creds` credential below must be mapped into the cluster's `aws-auth` ConfigMap (or
EKS access entries) with permissions to apply manifests — otherwise `kubectl apply` will
succeed in authenticating to AWS but fail with an `Unauthorized` error from the cluster itself.

---

## 8. Configure Jenkins credentials

### 8.1 AWS Credentials plugin

1. **Manage Jenkins → Plugins → Available plugins** → search `AWS Credentials` → install.
2. **Manage Jenkins → Credentials → System → Global credentials → Add Credentials**
3. Kind: **AWS Credentials**
   - ID: `AWS_Creds`
   - Access Key ID / Secret Access Key: from an IAM user with EKS access in Account B
4. Click **Create**. Jenkins will confirm the credentials are valid and list the availability
   zones it can see.

### 8.2 Docker Hub credentials

1. On Docker Hub: **Account Settings → Personal access tokens → Generate new token**
   - Description: `jenkins-pipeline-PAT`
   - Permissions: `Read, Write, Delete`
2. In Jenkins: **Add Credentials → Username with password**
   - Username: your Docker Hub username
   - Password: the generated access token (not your account password)
   - ID: `Docker_Creds`

### 8.3 Docker Hub repository

Create the target repository manually on Docker Hub **before** the first push (e.g.
`deepbijwe/node-application`) — see Troubleshooting §2 for why this matters.

---

## 9. The Jenkinsfile

```groovy
pipeline{
    agent any
    environment {
        DOCKER_REPO = "deepbijwe"
        DOCKER_USER = "node-application"
        IMAGE_NAME = "node-app"
        CONTAINER_NAME = "node-container"
        AWS_REGION = "ap-south-1"
        CLUSTER_NAME = "demo-ekscluster"
    }
    stages {
        stage('Checkout/clone') { ... }
        stage('Verify Environment') { ... }
        stage('Install Dependencies') { ... }
        stage('Run Tests') { ... }
        stage('Build Docker Image') { ... }
        stage('Docker login') { ... }
        stage('Docker push') { ... }
        stage('Update Manifest') { ... }
        stage('Configure EKS') { ... }
    }
    post {
        always { cleanWs() }
    }
}
```

### Stage-by-stage

| Stage | What it does |
|---|---|
| Checkout/clone | Clones the app repo (`main` branch) into the Jenkins workspace |
| Verify Environment | Prints Node, npm, and Docker versions as a sanity check |
| Install Dependencies | `npm install` |
| Run Tests | `npm test` |
| Build Docker Image | `docker build -t ${DOCKER_REPO}:${BUILD_NUMBER} .` |
| Docker login | Authenticates to Docker Hub using `Docker_Creds` |
| Docker push | Re-tags the image as `${DOCKER_REPO}/${DOCKER_USER}:${BUILD_NUMBER}` and pushes it |
| Update Manifest | `sed`-replaces the `image:` line in `k8s/deployment.yaml` with the newly pushed tag |
| Configure EKS | Uses `AWS_Creds` to run `aws eks update-kubeconfig`, then `kubectl apply` on the deployment and service manifests |

**Important naming detail:** the image is pushed as `${DOCKER_REPO}/${DOCKER_USER}` (i.e.
`deepbijwe/node-application`). Make sure the `sed` command in **Update Manifest** references
that exact same combination — not the unused `IMAGE_NAME` variable — or pods will fail to
pull the image (see Troubleshooting §3).

---

## 10. Kubernetes manifests

`k8s/deployment.yaml` — deploys the app; its `image:` line is rewritten on every build by the
Update Manifest stage.

`k8s/service.yaml` — expose the app with a `LoadBalancer` type Service so it gets a public
AWS ELB endpoint:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: node-app-service
spec:
  type: LoadBalancer
  selector:
    app: node-app
  ports:
    - port: 80
      targetPort: 3000
```

After `kubectl apply -f k8s/service.yaml`, get the public endpoint with:

```bash
kubectl get svc
```

The `EXTERNAL-IP` column shows the ELB hostname — that's the URL to open in a browser.

---

## 11. Run it

Trigger a Jenkins build. On success:

```bash
kubectl get pods
kubectl get deploy
kubectl get svc
```

should show the deployment's pods `Running` and the service's external endpoint reachable
from a browser.

---

## Troubleshooting

### 1. `permission denied while trying to connect to the docker API at unix:///var/run/docker.sock`

**Cause:** the `jenkins` user isn't in the `docker` group, or was added to it *after* Jenkins
had already started (group membership is only re-read on process restart).

**Fix:**
```bash
usermod -aG docker jenkins
systemctl restart jenkins
```
Verify with `groups jenkins` before re-running the pipeline.

### 2. `push access denied ... insufficient_scope: authorization failed`

**Cause:** the target Docker Hub repository didn't exist yet. A push using a personal access
token can authenticate successfully but still get rejected if the repo has never been created.

**Fix:** manually create the repository on Docker Hub (e.g. `deepbijwe/node-application`)
before the first push, and confirm the access token's permissions are **Read, Write, Delete**
(not read-only).

### 3. Pods stuck in `ImagePullBackOff`

**Cause:** the image reference in `k8s/deployment.yaml` didn't match the image that was
actually pushed to Docker Hub — e.g. the manifest pointed at `node-app:<tag>` while the image
was pushed as `deepbijwe/node-application:<tag>`.

**Fix:** make sure the `sed` command in the **Update Manifest** stage builds the image
reference from the *same* variables used in the **Docker push** stage
(`${DOCKER_REPO}/${DOCKER_USER}:${BUILD_NUMBER}`), so the pulled tag always matches what was
pushed.

### Precautions taken

- Docker Hub PAT scoped to `Read, Write, Delete` only (no admin-level token used).
- AWS credentials for the EKS account stored as a dedicated Jenkins **AWS Credentials** entry
  (`AWS_Creds`), not hardcoded in the Jenkinsfile.
- Cross-account access verified by confirming `AWS_Creds` can see the target account's
  availability zones before relying on it in the **Configure EKS** stage.
- `cleanWs()` in `post { always { ... } }` keeps the Jenkins workspace clean between builds.