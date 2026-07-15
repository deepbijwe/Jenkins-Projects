# Jenkins Integration with Kubernetes (EKS) — Project Documentation

## Overview

This project sets up a Jenkins CI/CD pipeline that deploys an Nginx-based
application (`deepbijwe/k8s-ingress:netflix`) to an AWS EKS cluster. Jenkins
pulls **only one folder** (`k8s-Intergeration-Jenkins`) from a larger GitHub
repo using Git sparse checkout, then runs `kubectl apply` against the EKS
cluster to create a Deployment and a Service, exposed via NodePort. A GitHub
webhook triggers the pipeline automatically on every push.

**Repo:** `https://github.com/deepbijwe/Jenkins-Projects`
**Pipeline folder:** `k8s-Intergeration-Jenkins/`
**Cluster:** Amazon EKS (`demo-ekscluster`)

---

## Architecture

```
GitHub (push)
    │
    ▼
GitHub Webhook → Jenkins (http://<jenkins-host>:<port>/github-webhook/)
    │
    ▼
Jenkins Pipeline (sparse checkout → kubectl apply)
    │
    ▼
Kubernetes API Server (EKS control plane)
    │
    ▼
Worker Node → pulls image from Docker Hub → runs pods
```

---

## Step 1 — Give Jenkins access to AWS and Kubernetes

Jenkins runs as the `jenkins` system user, but `aws configure` and
`aws eks update-kubeconfig` were originally run as `root`. Jenkins needs its
own copies of the credentials/config.

```bash
# Confirm the jenkins user exists
tail -5 /etc/passwd
# jenkins:x:105:109:Jenkins:/var/lib/jenkins:/bin/bash

# Copy AWS CLI credentials/config to Jenkins home
cp -rvf .aws/ /var/lib/jenkins/
chown -R jenkins:jenkins /var/lib/jenkins/.aws/

# Copy kubeconfig (created by `aws eks update-kubeconfig --name demo-ekscluster`)
cp -rvf .kube/ /var/lib/jenkins/
chown -R jenkins:jenkins /var/lib/jenkins/.kube/
```

This lets the `jenkins` user run `aws` and `kubectl` commands against the
`demo-ekscluster` EKS cluster inside pipeline steps.

---

## Step 2 — Create the Kubernetes manifests

**`k8s-Intergeration-Jenkins/deployment.yaml`**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-deployment
  labels:
    app: nginx
spec:
  replicas: 2
  selector:
    matchLabels:
      app: nginx
  template:
    metadata:
      labels:
        app: nginx
    spec:
      containers:
        - name: nginx
          image: deepbijwe/k8s-ingress:netflix
          ports:
            - containerPort: 80
```

**`k8s-Intergeration-Jenkins/service.yaml`**

```yaml
apiVersion: v1
kind: Service
metadata:
  name: nginx-service
  labels:
    app: nginx
spec:
  type: NodePort
  selector:
    app: nginx
  ports:
    - protocol: TCP
      port: 80
      targetPort: 80
```

### 🐞 Mistake #1 — YAML indentation error under `ports`

Original YAML had:

```yaml
containers:
  - name: nginx
    image: deepbijwe/k8s-ingress:netflix
    ports:
  - containerPort: 80
```

`- containerPort: 80` was indented at the **same level as `- name: nginx`**,
so Kubernetes parsed it as a second container list item instead of a port
entry inside `ports`.

**Fix:** indent `- containerPort: 80` one level deeper, nested under `ports:`:

```yaml
ports:
  - containerPort: 80
```

**How to validate YAML before applying:**

```bash
kubectl apply --dry-run=client -f deployment.yaml
kubectl apply --dry-run=client -f deployment.yaml -o yaml   # view parsed result
```

---

## Step 3 — Set up Git sparse checkout (pull only one folder)

The GitHub repo `Jenkins-Projects` contains multiple unrelated project
folders (`Git-pull-&-Maven-Build`, `Jenkins-installation-ec2`,
`SonarQube-Test-Code`, `k8s-Intergeration-Jenkins`, ...). Only the
`k8s-Intergeration-Jenkins` folder is needed for this pipeline, so Git
**sparse checkout** is used instead of cloning the whole repo.

**Manual sparse checkout (for reference):**

```bash
git clone --filter=blob:none --no-checkout https://github.com/deepbijwe/Jenkins-Projects.git
cd Jenkins-Projects
git sparse-checkout init --cone
git sparse-checkout set k8s-Intergeration-Jenkins
git checkout main
```

**In Jenkins (Pipeline script from SCM → Git):**

1. **Repository URL:** `https://github.com/deepbijwe/Jenkins-Projects.git`
2. **Credentials:** `- none -` (public repo)
3. **Branch Specifier:** `*/main`
4. **Additional Behaviours → Add → Sparse Checkout paths**
   - **Path:** `k8s-Intergeration-Jenkins`
5. **Script Path:** `k8s-Intergeration-Jenkins/Jenkinsfile`
6. Leave **Lightweight checkout** enabled.

This is equivalent to the following Jenkins `checkout` step used in a
scripted/declarative pipeline:

```groovy
checkout([
    $class: 'GitSCM',
    branches: [[name: '*/main']],
    userRemoteConfigs: [[
        url: 'https://github.com/deepbijwe/Jenkins-Projects.git'
    ]],
    extensions: [[
        $class: 'SparseCheckoutPaths',
        sparseCheckoutPaths: [[
            path: 'k8s-Intergeration-Jenkins'
        ]]
    ]]
])
```

### 🐞 Mistake #2 — Wrong/misspelled sparse checkout path

The folder is named `k8s-Intergeration-Jenkins` (note: "Intergeration",
not the correctly spelled "Integration") throughout the repo, Jenkinsfile,
and manifests. Git sparse checkout paths are **case- and spelling-sensitive**.
If the path entered in Jenkins doesn't exactly match the folder name in the
repo, checkout silently succeeds but the folder is empty, or fails with:

```
fatal: 'k8s-Intergeration-Jenkins' is not a directory
```

**Fix:** confirm the exact folder name in GitHub first:

```bash
git ls-tree -d --name-only origin/main
```

Then use that exact string (matching case and spelling) in the Sparse
Checkout **Path** field, the Jenkins **Script Path**, and every `sh` step
that references the folder.

---

## Step 4 — Write the Jenkinsfile

**`k8s-Intergeration-Jenkins/Jenkinsfile`**

```groovy
pipeline {
    agent any

    stages {
        stage('Checkout Selected Folder from Git') {
            steps {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: '*/main']],
                    userRemoteConfigs: [[
                        url: 'https://github.com/deepbijwe/Jenkins-Projects.git'
                    ]],
                    extensions: [[
                        $class: 'SparseCheckoutPaths',
                        sparseCheckoutPaths: [[
                            path: 'k8s-Intergeration-Jenkins'
                        ]]
                    ]]
                ])
            }
        }

        stage('Verify') {
            steps {
                sh 'ls -l'
                sh 'ls -l k8s-Intergeration-Jenkins'
            }
        }

        stage('deployement') {
            steps {
                sh 'kubectl apply -f k8s-Intergeration-Jenkins/deployment.yaml'
                sh 'kubectl apply -f k8s-Intergeration-Jenkins/service.yaml'
            }
        }

        stage('check deployment status') {
            steps {
                sh 'kubectl get pods'
                sh 'kubectl get pods -o wide'
                sh 'kubectl get svc'
            }
        }
    }
}
```

> Optional, more verbose status stage for better console output:
> ```groovy
> stage('Deployment Status') {
>     steps {
>         sh 'kubectl rollout status deployment/nginx-deployment'
>         sh 'kubectl describe deployment nginx-deployment'
>         sh 'kubectl get pods -o wide'
>         sh 'kubectl get svc'
>     }
> }
> stage('Application Logs') {
>     steps {
>         sh 'kubectl logs -l app=nginx --tail=50'
>     }
> }
> ```

### 🐞 Mistake #3 — `ERROR: Unable to find Jenkinsfile from git ...`

```
Started by user train-with-deep
ERROR: Unable to find Jenkinsfile from git https://github.com/deepbijwe/Jenkins-Projects.git
Finished: FAILURE
```

This means Jenkins connected to the repo fine, but couldn't find the
Jenkinsfile at the path it expected. Two variants of this happened during
setup:

1. **Script Path left as the default `Jenkinsfile`**, while the actual file
   lived at `k8s-Intergeration-Jenkins/Jenkinsfile`.
   **Fix:** set **Script Path** to `k8s-Intergeration-Jenkins/Jenkinsfile`.
2. **The pipeline file wasn't named `Jenkinsfile` at all** — at one point it
   existed as `k8s-Intergeration-Jenkins/pipeline.groovy`.
   **Fix (two options):**
   - Either point **Script Path** at the actual filename:
     `k8s-Intergeration-Jenkins/pipeline.groovy`, or
   - Rename `pipeline.groovy` → `Jenkinsfile` in the repo (recommended,
     follows Jenkins convention) and set **Script Path** to
     `k8s-Intergeration-Jenkins/Jenkinsfile`.

Also double-checked:
- **Filename is exactly `Jenkinsfile`** — capital `J`, no extension (not
  `Jenkinsfile.txt` / `jenkinsfile`).
- **Branch Specifier** matches the actual default branch (`*/main`, not
  `*/master`).

> **Note on redundant checkout:** because the job type is *"Pipeline script
> from SCM"*, Jenkins already checks out the repository once just to fetch
> the Jenkinsfile. Adding another `checkout(...)` stage inside the pipeline
> is only necessary if sparse-checkout paths need to be enforced explicitly
> (as done here) — otherwise it's redundant.

---

## Step 5 — Configure the GitHub webhook trigger

**In Jenkins (job → Configure → Triggers):**
- ✅ **GitHub hook trigger for GITScm polling**

**In GitHub (repo → Settings → Webhooks → Add webhook):**

| Field | Value |
|---|---|
| Payload URL | `http://<jenkins-host>:<port>/github-webhook/` (must end with a trailing `/`) |
| Content type | `application/json` |
| Secret | left blank (no webhook secret configured in Jenkins) |
| Events | Just the push event |

Flow once configured:

```
git push origin main
        │
        ▼
GitHub sends POST → http://<jenkins-host>:<port>/github-webhook/
        │
        ▼
Jenkins matches jobs with "GitHub hook trigger for GITScm polling"
        │
        ▼
Jenkins starts the build automatically
```

Verify delivery under **GitHub → Settings → Webhooks → Recent Deliveries**
(look for a green check / `200` response).

### 🐞 Mistake #4 — Wrong webhook port (used Jenkins' internal port instead of the Service's NodePort)

Jenkins itself was running **inside the Kubernetes cluster**, exposed via a
`NodePort` Service:

```
jenkins-service   NodePort   10.100.21.48   <none>   8001:30702/TCP
```

The webhook Payload URL was initially assumed to be on port `8080` (the
container's internal Jenkins port), but since Jenkins is only reachable
externally through the **NodePort (`30702`)** on the **worker node's public
IP**, the correct webhook URL was:

```
http://<worker-node-public-ip>:30702/github-webhook/
```

not `http://<worker-node-public-ip>:8080/github-webhook/`.

**Lesson:** when Jenkins is exposed through a Kubernetes NodePort, always use
the **NodePort**, not the container's internal port, in any external URL
(webhooks, browser access, etc.). Also confirm the EC2/worker node's Security
Group allows inbound TCP on that NodePort.

---

## Step 6 — Deploy and verify

Successful pipeline console output:

```
+ kubectl apply -f k8s-Intergeration-Jenkins/deployment.yaml
deployment.apps/nginx-deployment unchanged
+ kubectl apply -f k8s-Intergeration-Jenkins/service.yaml
service/jenkins-service unchanged
+ kubectl get pods
nginx-deployment-5987bf6468-86hcn   1/1   Running   0   11m
nginx-deployment-5987bf6468-prstx   1/1   Running   0   11m
+ kubectl get pods -o wide
...
Finished: SUCCESS
```

`unchanged` simply means the object already existed and matched the applied
manifest — not an error.

### 🐞 Mistake #5 — Pipeline succeeds, but no application output in the console

A successful `Finished: SUCCESS` only means the `kubectl apply` and status
commands ran without error — Jenkins does **not** automatically show
container/application logs. To actually see what's happening inside the
pods, logging steps have to be added explicitly:

```groovy
sh 'kubectl logs -l app=nginx --tail=50'
```

or run manually:

```bash
kubectl get pods
kubectl logs <pod-name>
```

### 🐞 Mistake #6 — Confusing image pull credentials with Jenkins credentials

The container image `deepbijwe/k8s-ingress:netflix` is a **custom image**,
but no Docker Hub credentials were ever given to Jenkins — and that's
correct, because:

- Jenkins **never pulls the image**. It only runs `kubectl apply -f
  deployment.yaml` against the Kubernetes API server.
- The **worker node's container runtime** (not Jenkins) pulls the image
  directly from Docker Hub.
- Since `deepbijwe/k8s-ingress` is a **public** Docker Hub repo, the pull
  works anonymously — no `imagePullSecrets` needed.

Credentials would only be required in two cases:
1. The image lives in a **private** registry → the pod would fail with
   `ImagePullBackOff` / `ErrImagePull`, and a Docker registry secret would be
   needed:
   ```bash
   kubectl create secret docker-registry dockerhub-secret \
     --docker-server=https://index.docker.io/v1/ \
     --docker-username=<username> \
     --docker-password=<password>
   ```
   referenced in the Deployment via:
   ```yaml
   spec:
     imagePullSecrets:
       - name: dockerhub-secret
   ```
2. Jenkins itself **builds and pushes** the image (`docker build` /
   `docker push`) — not the case in this pipeline, which only deploys an
   already-published image.

### 🐞 Mistake #7 — Deployment succeeded, but the webpage wouldn't load (Service port mismatch)

Deployment and pods were healthy (`kubectl get pods` showed `Running`), and
the Service existed with a NodePort — but the app wasn't reachable in the
browser.

**Root cause:** the Service's `targetPort` didn't match the container's
actual listening port.

```yaml
# What existed (wrong):
ports:
  - protocol: TCP
    port: 8001
    targetPort: 8001   # nothing in the container listens on 8001
```

The container (`deployment.yaml`) exposes `containerPort: 80` — Nginx
listens on port 80 inside the pod — but the Service was forwarding to
`8001`, so traffic never reached the application.

**Fix:** match `targetPort` to the container's actual port:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: nginx-service
spec:
  type: NodePort
  selector:
    app: nginx
  ports:
    - protocol: TCP
      port: 80          # (or 8001, if you want that as the "external" port)
      targetPort: 80    # must match containerPort in the Deployment
```

**Diagnostic checklist used to find this:**

```bash
kubectl get svc                          # confirm NodePort assigned
kubectl get endpoints nginx-service      # confirm pods are behind the Service
kubectl describe svc nginx-service       # confirm selector / port / targetPort
kubectl exec -it <pod-name> -- curl localhost   # confirm app responds inside the pod
```

If `kubectl get endpoints` shows `<none>`, the Service `selector` doesn't
match the pod labels. If endpoints exist but the app still isn't reachable,
suspect a `port`/`targetPort` mismatch (as above) or a blocked Security
Group / firewall rule on the NodePort.

Also worth cleaning up: the Service was originally named `jenkins-service`
while actually exposing the **nginx** deployment — renamed to
`nginx-service` for clarity.

---

## Summary of all issues hit and fixes

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | Deployment YAML rejected/misinterpreted | `containerPort` indented at wrong level (sibling of container, not nested in `ports`) | Re-indent `- containerPort: 80` under `ports:` |
| 2 | `fatal: '...' is not a directory` on sparse checkout | Folder path typo/case mismatch | Verify exact name with `git ls-tree -d --name-only origin/main` |
| 3 | `ERROR: Unable to find Jenkinsfile from git ...` | Script Path pointed to root `Jenkinsfile`, but file was nested (or named `pipeline.groovy`) | Set Script Path to `k8s-Intergeration-Jenkins/Jenkinsfile` (rename file if needed) |
| 4 | Webhook not triggering / wrong URL assumed | Used Jenkins' internal port (8080) instead of the NodePort Jenkins is actually exposed on | Use `http://<node-ip>:<NodePort>/github-webhook/` |
| 5 | Pipeline "SUCCESS" but no visible output | Jenkins only shows command output, not container/app logs | Add `kubectl logs` steps explicitly |
| 6 | Confusion over missing Docker credentials | Jenkins doesn't pull images — the K8s worker node does, and the image is public | No credentials needed for public images; only needed for private registries or if Jenkins builds/pushes images |
| 7 | App deployed but webpage unreachable | Service `targetPort` (8001) didn't match container's actual port (80) | Set `targetPort: 80` to match `containerPort: 80` |

---

## Reference commands cheat sheet

```bash
# AWS / kubeconfig setup for Jenkins user
cp -rvf .aws/ /var/lib/jenkins/  && chown -R jenkins:jenkins /var/lib/jenkins/.aws/
cp -rvf .kube/ /var/lib/jenkins/ && chown -R jenkins:jenkins /var/lib/jenkins/.kube/

# Validate manifests before applying
kubectl apply --dry-run=client -f deployment.yaml
kubectl apply --dry-run=client -f deployment.yaml -o yaml

# Sparse checkout (manual/local)
git clone --filter=blob:none --no-checkout <repo-url>
cd <repo>
git sparse-checkout init --cone
git sparse-checkout set <folder-name>
git checkout main

# Confirm exact folder names in a repo/branch
git ls-tree -d --name-only origin/main

# Deployment & service status
kubectl get pods
kubectl get pods -o wide
kubectl get svc
kubectl get endpoints <service-name>
kubectl describe svc <service-name>
kubectl rollout status deployment/<name>
kubectl logs -l app=<label> --tail=50
kubectl exec -it <pod-name> -- curl localhost
```