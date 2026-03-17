# CDK Module – Infrastructure for Serverless ClamAV Scanning

This module defines and deploys the AWS infrastructure that powers the ClamAV virus scanning Lambda using AWS CDK in Java.

---

## 🚀 What It Does

- **Deploys a container-based Lambda function** that scans S3 uploads for viruses using ClamAV.
- **Wires existing S3 buckets** to invoke the scanner on object creation events.
- **Tags each file** with a `scan-status` tag based on configuration and scan outcome.

---

## 🔧 How It Works

- Uses a **Dockerfile** to build a Lambda image with:
  - Java 25 Lambda base image on Amazon Linux 2023
  - ClamAV packages installed from the same AL2023 runtime family
  - Latest virus definitions from `freshclam`
  - Your Lambda JAR (`lambda-1.0.jar`)
- The image is deployed via `DockerImageAsset` and used in a `DockerImageFunction`.

---

## 📦 Build Integration

- The Lambda project builds `lambda-1.0.jar` and copies it into this module under `lambda-jar/`.
- Docker uses this copied JAR when building the container.

---

## 🧱 Stack Resources

- ✅ Lambda function (container-based, Java 25, ARM64)
- ✅ Existing S3 bucket subscriptions via event notification trigger
- ✅ IAM roles with scoped permissions for tag access

---

## 🛠 Tech Stack

- **AWS CDK (Java)**
- **Java 25 Lambda Runtime**
- **Docker (single-stage AL2023-based build)**
- **ARM64 container image**
- **ClamAV (AL2023-based)**

---

## 📌 Notes

- Using **ARM64** improves cold start times and reduces Lambda cost.
- The default deployment path targets ARM64; CloudShell falls back to x86_64 for convenience.
- Image architecture is selected in CDK, not inferred from the developer machine alone. A normal x86 host may still build the ARM image if Docker emulation/buildx is available, while CloudShell explicitly switches the stack to x86_64.
