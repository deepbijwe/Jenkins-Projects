# Jenkins CI/CD with SonarQube Code Quality Analysis

Integrating a self-hosted **SonarQube** server with a **Jenkins** pipeline running on a separate EC2 instance, so every build of the `onlinebookstore` Java project is automatically scanned for bugs, vulnerabilities, and code smells — with the pipeline gated on the Quality Gate result.

## Architecture

```
                  VPC (ap-south-1 / Mumbai)
   ┌─────────────────────────────┐   ┌─────────────────────────────┐
   │   Jenkins EC2               │   │   SonarQube EC2             │
   │   Public IP: 3.108.222.100  │   │   Public IP: 13.203.21.194  │
   │   Port: 8080                │──▶│   Private IP: 172.31.27.176 │
   │                             │   │   Port: 9000                │
   │   - Git checkout            │   │   - Code analysis engine    │
   │   - Maven build             │   │   - Quality Gate            │
   │   - SonarScanner (mvn)      │◀──│   - Webhook → Jenkins        │
   └─────────────────────────────┘   └─────────────────────────────┘
         GitHub: onlinebookstore          SG: port 9000 restricted
                                            to Jenkins private IP +
                                            admin IP only
```

## Step 1 — Lock down the SonarQube EC2 Security Group

Rather than opening port 9000 to the world, the inbound rule only allows traffic from the Jenkins server's private IP (plus SSH and a personal IP for dashboard access):

| Type | Port | Source | Purpose |
|---|---|---|---|
| SSH | 22 | 0.0.0.0/0 | admin access |
| Custom TCP | 9000 | `<jenkins-private-ip>/32` | Jenkins → SonarQube scans |
| Custom TCP | 9000 | `<my-public-ip>/32` | viewing the dashboard in browser |

AWS flags `0.0.0.0/0` on port 9000 as a risk — avoided that and scoped it to known IPs only.

Verified connectivity directly from the Jenkins EC2 shell:

```bash
curl http://172.31.27.176:9000
# returns raw SonarQube login page HTML — confirms network path + service are both up
```

## Step 2 — Log in to SonarQube and generate an analysis token

Logged in to the SonarQube web UI (`http://<sonarqube-public-ip>:9000`) with the default `admin` account, then generated a token for Jenkins to use instead of a raw password:

**My Account → Security → Generate Tokens**
- Name: `jenkins-integration`
- Type: `Global Analysis Token`
- Expires: `No expiration`

Token copied immediately (shown only once).

## Step 3 — Install the correct Jenkins plugin

⚠️ **Gotcha:** first attempt installed **Sonargraph Integration**, a different, unrelated plugin (Sonargraph ≠ SonarQube — similar names, different tools). This shows up as a "Sonargraph License Server" section under Manage Jenkins → System, not "SonarQube servers", so it can't be used for SonarQube integration.

Corrected by installing the right one:

**Manage Jenkins → Plugins → Available plugins** → search `sonar` → select **SonarQube Scanner** (not Sonargraph, not Quality Gates) → Install → restart Jenkins.

## Step 4 — Store the SonarQube token as a Jenkins credential

**Manage Jenkins → Credentials → System → Global credentials → Add Credentials**
- Kind: `Secret text`
- Secret: `<the token from Step 2>`
- ID: `sonarqube-token`
- Description: `token for sonar test the source code`

## Step 5 — Register the SonarQube server in Jenkins

**Manage Jenkins → System → SonarQube servers**
- ✅ Environment variables checkbox
- Name: `SonarQubeServer` (must match `withSonarQubeEnv('SonarQubeServer')` in the pipeline)
- Server URL: `http://172.31.27.176:9000`
- Server authentication token: selected the `sonarqube-token` credential from Step 4

## Step 6 — Write the pipeline

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

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQubeServer') {
                    sh '''
                        mvn sonar:sonar \
                        -Dsonar.projectKey=onlinebookstore \
                        -Dsonar.host.url=http://172.31.27.176:9000
                    '''
                }
            }
        }
    }
}
```

## Step 7 — Mistakes made & how each was diagnosed

### Mistake 1: Tested connectivity against the wrong private IP

From the Jenkins EC2 shell, the first connectivity check used the wrong IP (mistakenly reused the Jenkins box's own private IP instead of SonarQube's):

```bash
root@ip-172-31-28-243:~# curl http://172.31.28.243:9000
curl: (7) Failed to connect to 172.31.28.243 port 9000 after 0 ms: Could not connect to server
```

That IP (`172.31.28.243`) is the **Jenkins EC2's own private IP**, not SonarQube's — so of course nothing was listening on port 9000 there. Re-ran against the correct SonarQube private IP and got the expected response:

```bash
root@ip-172-31-28-243:~# curl http://172.31.27.176:9000
<!doctype html>
<html lang="en">
...
<title>SonarQube</title>
```

**Lesson:** always double-check *which* box's IP you're pasting — a `Connection refused`/`Could not connect` error on a port that should be open is often just a wrong-target mistake, not a firewall or service problem. Confirm the IP against the actual EC2 instance details before assuming it's a network/SG issue.

### Mistake 2: Installed the wrong Jenkins plugin (Sonargraph instead of SonarQube Scanner)

Covered in Step 3 above — searching "sonar" in the plugin manager surfaced **Sonargraph Integration** near the top of results, and it got installed instead of **SonarQube Scanner**. This wasn't caught until the pipeline actually ran, because Jenkins doesn't error out at plugin-install time — the mismatch only surfaces when the pipeline tries to use a step the wrong plugin doesn't provide.

### Mistake 3: Pipeline failed with `NoSuchMethodError` on `withSonarQubeEnv`

Build #6 failed at the `SonarQube Analysis` stage with:

```
java.lang.NoSuchMethodError: No such DSL method 'withSonarQubeEnv' found among steps [archive, bat,
build, catchError, checkout, deleteDir, dir, echo, emailext, emailextrecipients, envVarsForTool,
error, fileExists, findBuildScans, getContext, git, hideFromView, input, isUnix, junit, library,
libraryResource, load, mail, milestone, node, parallel, powershell, properties, publishChecks,
pwd, pwsh, readFile, readScmFile, readTrusted, resolveScm, retry, script, sh, sleep, stage, stash,
step, timeout, timestamps, tm, tool, unarchive, unstable, unstash, validateDeclarativePipeline,
waitForBuild, waitUntil, warnError, withChecks, withContext, withCredentials, withEnv, withGradle,
wrap, writeFile, ws] or symbols [GitUsernamePassword, SonarghraphReport, agent, all, ...]
```

**How this was diagnosed:** the error message itself lists every DSL step/symbol Jenkins currently recognizes — `withSonarQubeEnv` is conspicuously absent, while a Sonargraph-specific symbol (`SonarghraphReport`) *is* present in the list. That's the direct evidence the wrong plugin was still active: the correct step genuinely didn't exist in this Jenkins instance yet, it wasn't a typo or syntax issue.

**Root cause:** the wrong plugin (Sonargraph) was installed in Step 3 at the time this build ran — it doesn't provide the `withSonarQubeEnv` pipeline step at all, since that step is only registered by the **SonarQube Scanner** plugin.

**Fix:** went back to **Manage Jenkins → Plugins → Available plugins**, searched `sonar` again, and this time carefully selected **SonarQube Scanner** (confirmed by its description: *"allows an easy integration of SonarQube, the open source platform for Continuous Inspection of code quality"*) rather than Sonargraph Integration. Installed it and restarted Jenkins via the Restart Safely screen. After the restart, `withSonarQubeEnv` appeared as a valid step and the **SonarQube servers** section (Step 5) became available under Manage Jenkins → System — confirming the correct plugin was now active.

## Step 8 — Re-run: build #7 succeeds

```
ANALYSIS SUCCESSFUL, you can find the results at: http://172.31.27.176:9000/dashboard?id=onlinebookstore
BUILD SUCCESS
Total time: 19.820 s
Finished: SUCCESS
```

Jenkins job page confirms:

```
SonarQube Quality Gate
onlinebookstore   Passed
server-side processing: Success
```

## Step 9 — Verify results on the SonarQube dashboard

Opened `http://<sonarqube-public-ip>:9000/dashboard?id=onlinebookstore`:

- **Quality Gate:** ✅ Passed
- 2.4k lines of code analyzed
- Security: 18 open issues
- Reliability: 86 open issues
- Maintainability: 121 open issues
- Coverage: 0.0% (no test coverage reports wired in yet)
- Duplications: 41.8%

## Key troubleshooting takeaways

1. **Verify the target IP before blaming the network.** A `Connection refused` on the "right" port can just mean the command was pointed at the wrong host (Jenkins' own IP instead of SonarQube's) — check which instance the IP actually belongs to first.
2. **Sonargraph ≠ SonarQube.** Same-sounding plugin names in the Jenkins plugin catalog can send you down the wrong path entirely — always confirm the plugin name and description, and that the resulting config section matches ("SonarQube servers", not "Sonargraph License Server").
3. **Read `NoSuchMethodError`'s step/symbol list closely.** Jenkins prints every DSL step it currently recognizes when a step is missing — scanning that list for the expected step (absent) and any competing plugin's symbols (present) pinpoints a plugin problem versus a syntax problem in seconds.
4. **Security group source scoping matters.** Rules filter by request *source IP*, not by which IP (public/private) you use to reach the instance — so a rule allowing only Jenkins' private IP will correctly block a browser hitting the public IP, by design.

## Next steps

- [ ] Add a `waitForQualityGate` stage + SonarQube webhook (`http://<jenkins-ip>:8080/sonarqube-webhook/`) to fail the pipeline automatically on a Quality Gate failure
- [ ] Wire in JaCoCo for test coverage reporting (currently 0.0%)
- [ ] Address the 41.8% code duplication flagged in the scan