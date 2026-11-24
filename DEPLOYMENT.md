# NEXORA Cloud Deployment Guide: Render Free Web Service

This guide explains how to deploy **NEXORA** to [Render.com](https://render.com) using its free Docker Web Service tier.

---

## 1. Cloud Architecture Overview

When deployed to Render, NEXORA runs inside a lightweight Alpine container running Java 21:

- **Web Server:** Embedded Java 21 `HttpServer` utilizing Virtual Threads.
- **Port Binding:** Dynamically binds to `0.0.0.0:$PORT` provided by Render.
- **AI Backend:** Operates in `PUBLIC` mode connecting over HTTPS to OpenRouter free models.
- **No Local Dependencies:** Does not require a personal computer or local Ollama instance.
- **Zero Framework Footprint:** Compiles with standard `javac`, requiring no Maven, Gradle, or Spring dependencies.
- **Zero PHP Dependency:** Pure Java 21 backend with no PHP runtime or legacy code anywhere in the container.
- **No PowerShell Required:** Deploys via standard Dockerfile and Render blueprint without any PowerShell dependency.

---

## 2. Render Free Web Service Setup Steps

### Step 1: Push Repository to GitHub
Ensure your repository is pushed to your personal GitHub account. The repository should contain:
- `Dockerfile`
- `src/`
- `frontend/`
- `render.yaml`

### Step 2: Create a New Web Service on Render
1. Log in to [Render Dashboard](https://dashboard.render.com).
2. Click **New +** and select **Web Service**.
3. Choose **Build and deploy from a Git repository**.
4. Connect and select your `Personal-AI-Chatbot` (or `nexora`) repository.

### Step 3: Configure Service Settings
- **Name:** `nexora-ai` (or your preferred name)
- **Region:** Choose the region closest to you (e.g., Oregon, Frankfurt, Singapore)
- **Branch:** `main` (or `master`)
- **Runtime:** `Docker`
- **Instance Type:** `Free` (0.1 CPU, 512 MB RAM)

### Step 4: Configure Environment Variables
In the **Environment Variables** section on Render, add the following keys:

| Key | Value | Description |
| :--- | :--- | :--- |
| `APP_MODE` | `PUBLIC` | Enables cloud OpenRouter service |
| `OPENROUTER_API_KEY` | `sk-or-v1-...` | Your free API key from [OpenRouter](https://openrouter.ai/keys) |
| `OPENROUTER_DEFAULT_MODEL` | `meta-llama/llama-3.2-3b-instruct:free` | Default free inference model |
| `RATE_LIMIT_PER_MINUTE` | `30` | In-memory sliding window rate limiter |
| `MAX_REQUEST_SIZE_BYTES` | `1048576` | 1 MB payload protection |
| `REQUEST_TIMEOUT_SECONDS` | `180` | Upstream provider timeout |

> **Security Note:** Never commit your `OPENROUTER_API_KEY` into Git. Only enter it directly in the Render dashboard.

### Step 5: Deploy
Click **Create Web Service**. Render will automatically:
1. Build the multi-stage Docker container.
2. Compile Java sources with OpenJDK 21.
3. Start the application on the assigned port.
4. Verify the container health at `GET /api/health`.

Once deployed, your application will be accessible at:
`https://<your-service-name>.onrender.com`

---

## 3. Render Free Tier Characteristics & Behavior

- **Sleep on Inactivity:** Free web services spin down after 15 minutes of inactivity. When a new visitor accesses the URL, Render wakes the container (cold start takes roughly 40-60 seconds).
- **RAM Limit:** Render Free tier provides 512 MB RAM. NEXORA's Core Java server consumes only ~40 MB of heap memory, operating well within free tier boundaries.
- **Zero Payment Card Needed:** Creating and running free web services on Render does not require credit card entry.

---

## 4. Curated Free Models on OpenRouter

When running in `PUBLIC` mode, NEXORA supports multiple free models with zero card requirement:

1. `meta-llama/llama-3.2-3b-instruct:free` (Recommended fast default)
2. `google/gemini-2.0-flash-lite:free`
3. `deepseek/deepseek-r1:free`
4. `mistralai/mistral-7b-instruct:free`

---

## 5. Local Docker Testing

Before pushing to Render, you can test the Docker build locally on any machine with Docker installed:

```bash
# Build the container
docker build -t nexora:latest .

# Run container in Public mode
docker run -d -p 8080:8080 \
  -e APP_MODE=PUBLIC \
  -e OPENROUTER_API_KEY=your_key_here \
  nexora:latest

# Verify health
curl -s http://127.0.0.1:8080/api/health
```
