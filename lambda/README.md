# Lambda: ClamAV Virus Scanning Function

This module contains the AWS Lambda implementation for scanning S3 objects for viruses using **ClamAV**. Built with **Java 25**, this high-performance, serverless function uses a **container-based deployment** and leverages the **AWS SDK v2 Async Client with CRT (Common Runtime)** for optimal performance.

---

## 🚀 Features

- ✅ **Java 25** with virtual thread readiness
- 🔬 **ClamAV** integration (with up-to-date virus definitions)
- ☁️ **Asynchronous S3 interactions** via `S3AsyncClient` + CRT (zero-copy, event-driven I/O)
- 🐳 **Container-based Lambda deployment** using the Java 25 Lambda base image with AL2023-native ClamAV packages
- 🧠 **Smart object tagging**: adds `scan-status` tags such as `SCANNING`, `CLEAN`, `INFECTED`, `ERROR`, or `FILE_SIZE_EXCEEED` depending on config and outcome
- ⚡ **Parallel processing**: Uses `CompletableFuture` for high concurrency
- 🧼 **/tmp-safe**: Streams S3 content directly to `/tmp`, deletes after scan

---

## ⚙️ How It Works

1. **Triggered by S3 Event Notification**
2. **Downloads file** to Lambda `/tmp` using `S3AsyncClient`
3. **Executes `clamscan`** in a native container image with preloaded virus definitions
4. **Parses output** and only treats the scan as infected when ClamAV reports a real `FOUND` signature
5. **Tags file** in-place with `scan-status` when configured to do so

---

## 📁 Build Output

The `target/lambda-1.0.jar` file is automatically copied to the CDK module during Maven build to be included in the container image.

---

## 🧰 Technologies Used

- Java 25
- AWS SDK v2 with CRT
- Log4j2
- Maven Shade Plugin
- ClamAV
- Docker & AWS Lambda Container Images
