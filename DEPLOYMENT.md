# NEXORA: Zero-Budget Cloud & Local Deployment Guide

This guide explains how to deploy and operate **NEXORA – AI-Powered Personal Assistant** at **$0/month total cost** across local environments, free cloud hosting (Render), and static CDN hosting (Cloudflare Pages), including full Progressive Web App (PWA) installation.

---

## 1. Operating Modes Overview

NEXORA features a strict dual-mode architecture:

| Feature | LOCAL Mode | PUBLIC Mode |
| :--- | :--- | :--- |
| **Primary Use Case** | Private personal desktop assistant | Publicly accessible web companion |
| **Hosting** | Local workstation / laptop | Cloud Docker (Render Free, Fly.io, Railway) |
| **AI Inference** | Local [Ollama](https://ollama.ai) (`qwen2.5:7b`, `qwen2.5:3b`) | OpenRouter Cloud Free Meta-Router (`openrouter/free`) |
| **Cost** | $0 (100% free, offline) | $0 (free-tier cloud compute & free-tier AI) |
| **Personal Memory Storage** | Local disk (`data/memories/*.json`) | **100% Client-Side Browser `localStorage`** |
| **Model Management UI** | Full: Pull, Delete, Profiles, Telemetry | Safe: Cloud catalog only; local pull/delete return `403` |
| **Client BYOK** | Optional | Supported via Settings modal (`X-OpenRouter-Key`) |

---

## 2. Zero-Budget Public Architecture

### Privacy & Data Isolation Architecture
On free-tier cloud containers (like Render), filesystems are **ephemeral** (wiped every 15 minutes when the container sleeps). Furthermore, sharing a single server-side memory directory among anonymous public web visitors creates privacy leakage risks.

To solve both problems at $0 cost:
1. **Client-Side Isolation:** In `PUBLIC` mode, conversations and personal memories are stored strictly in the visitor's browser `localStorage` (`nexora_memories_v1`).
2. **Stateless Prompt Injection:** The frontend injects the user's active memories directly into the `/api/chat` request body. The Java backend uses virtual threads to format the system prompt, processes inference, and returns memory candidates without writing anything to server disk.
3. **Zero Cross-Talk:** No visitor can ever read, overwrite, or access another visitor's memory or chat history.

### Upstream AI Rate Limits & BYOK Support
- **Default Router:** NEXORA defaults to OpenRouter's official `openrouter/free` meta-router. This dynamically chooses from currently available, non-rate-limited free cloud models without hardcoding brittle model IDs.
- **Upstream Quota Reality:** Upstream free models have dynamic capacity limits (typically 10–20 requests/minute across all public users).
- **Bring Your Own Key (BYOK):** In the Settings modal, any user can paste their own free [OpenRouter API Key](https://openrouter.ai/keys). The key is stored purely in client `localStorage` and sent via `X-OpenRouter-Key` header, bypassing shared public server throttles.

---

## 3. Option A: Full-Stack Cloud Deployment on Render (Recommended)

Deploy both the Core Java 21 backend and vanilla frontend together as a single free Docker Web Service.

### Step 1: Push Repository to GitHub
Ensure your repository is pushed to your GitHub account with the following root files:
- `Dockerfile`
- `render.yaml`
- `src/`
- `frontend/`

### Step 2: Create Web Service on Render
1. Open the [Render Dashboard](https://dashboard.render.com).
2. Click **New +** > **Web Service**.
3. Connect your GitHub repository.
4. Fill in the service configuration:
   - **Name:** `nexora-ai`
   - **Region:** Oregon, Frankfurt, or Singapore
   - **Branch:** `main`
   - **Runtime:** `Docker`
   - **Instance Type:** `Free` (0.1 CPU, 512 MB RAM)

### Step 3: Configure Environment Variables
Set these variables in the Render Dashboard (**Environment** tab):

| Variable | Value | Explanation |
| :--- | :--- | :--- |
| `APP_MODE` | `PUBLIC` | Enables cloud mode and locks mutation endpoints |
| `OPENROUTER_DEFAULT_MODEL` | `openrouter/free` | Dynamic free router for cloud inference |
| `OPENROUTER_API_KEY` | `sk-or-v1-...` | Your free server key from [OpenRouter](https://openrouter.ai/keys) |
| `RATE_LIMIT_PER_MINUTE` | `30` | In-memory sliding window rate limit per client IP |
| `MAX_REQUEST_SIZE_BYTES` | `1048576` | 1 MB payload protection |
| `REQUEST_TIMEOUT_SECONDS` | `180` | Upstream provider timeout |

> **Security Note:** Never commit `OPENROUTER_API_KEY` into Git. Only enter it directly in the Render dashboard.

### Step 4: Deploy & Verify
Click **Create Web Service**. Render will build the multi-stage Alpine container and launch NEXORA.
Your public link will be:
`https://nexora-ai.onrender.com`

---

## 4. Option B: Decoupled CDN Frontend (Cloudflare Pages) + Render Backend

For maximum global responsiveness and zero cold-start latency on UI assets, you can host the `frontend/` directory on Cloudflare Pages (100% free) while pointing API calls to your Render backend.

1. **Deploy Frontend to Cloudflare Pages:**
   - Framework preset: `None`
   - Build output directory: `frontend`
2. **CORS & Decoupling:**
   - NEXORA's Java backend includes full CORS headers (`Access-Control-Allow-Origin: *`, `PUT`, `DELETE`, `X-User-Id`, `X-OpenRouter-Key`).
   - In the frontend Settings modal, enter your Render URL into **Backend API URL** (e.g. `https://nexora-ai.onrender.com`).
   - The frontend automatically remembers this backend in `localStorage` and routes all API calls to it.

---

## 5. Render Free Tier Operational Characteristics

- **Inactivity Sleep (Spin-Down):** Free Render instances sleep after 15 minutes of zero traffic.
- **Cold-Start Wakeup:** When a visitor opens the URL, Render spins up the container. Cold start takes 40–50 seconds. The frontend displays clean loading and retry indicators during this phase.
- **512 MB RAM Boundary:** The Dockerfile includes `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` to constrain Java 21 Virtual Thread heap memory below 384 MB, preventing container OOM kills.
- **Zero Cost Guarantee:** Render free web services require no credit card and never incur unexpected charges.

---

## 6. Progressive Web App (PWA) Installation

NEXORA includes a complete PWA manifest, service worker (`sw.js`), and high-resolution maskable PNG icons (`192x192` and `512x512`).

### On Android (Chrome / Brave / Edge)
1. Open the public HTTPS link in Chrome.
2. Tap the **Install NEXORA App** button in the sidebar or tap the browser menu (⋮) > **Install App** / **Add to Home screen**.
3. NEXORA will install as a standalone app with full dark theme support and offline shell caching.

### On iOS (Safari)
1. Open the public HTTPS link in Safari.
2. Tap the **Share** button (box with upward arrow).
3. Scroll down and tap **Add to Home Screen**.
4. Tap **Add**. NEXORA will launch in full-screen standalone mode without Safari browser chrome.

### On Desktop (Chrome / Edge / Windows / macOS)
1. Open the URL in Chrome or Edge.
2. Click the install icon in the URL omnibox or click **Install NEXORA App** in the sidebar.
3. NEXORA opens in an independent, distraction-free application window.

---

## 7. Local Docker Verification

To verify the container locally before deploying to Render:

```bash
# Build multi-stage Alpine image (~120 MB)
docker build -t nexora:latest .

# Run container in Public mode
docker run -d -p 8080:8080 \
  -e APP_MODE=PUBLIC \
  -e OPENROUTER_DEFAULT_MODEL=openrouter/free \
  -e OPENROUTER_API_KEY=your_key_here \
  nexora:latest

# Verify health endpoint
curl -s http://127.0.0.1:8080/api/health
```
