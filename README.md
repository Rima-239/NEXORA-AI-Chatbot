# NEXORA: AI-Powered Personal Chatbot

> **Think Smarter. Create Freely. Stay Private.**  
> *Your Private AI Companion powered by Core Java 21 and Vanilla Web Standards.*

---

## Overview

**NEXORA** is a portfolio-grade, full-stack AI conversational platform engineered to demonstrate advanced software architecture principles using standard libraries. It operates with **zero external backend frameworks** (no Spring, no Maven, no Gradle) while achieving high throughput via **Java 21 Virtual Threads**.

NEXORA offers a seamless dual-mode architecture:
1. **Local Mode (Default):** Runs completely offline using a local [Ollama](https://ollama.ai) instance (`qwen2.5:7b`), ensuring 100% data privacy and zero cloud egress.
2. **Public Mode:** Connects to free inference APIs (such as OpenRouter) for cloud deployment on platforms like Render, requiring zero local hardware dependencies.

---

## Key Features

- **Zero-Framework Java 21 Backend:** Built purely with `com.sun.net.httpserver` and `java.net.http.HttpClient`.
- **Zero PHP Dependency:** Pure Java 21 backend with no PHP runtime or legacy code anywhere in the application.
- **Zero Build Tool Overhead:** Compiles directly with standard `javac` (no Maven, no Gradle, no Spring Boot).
- **Native Scripting:** Standard Windows Batch scripts (`.bat`) and Unix shell scripts (`.sh`); PowerShell is not required to build, run, or deploy.
- **High-Concurrency Virtual Threads:** Thread-per-task executor capable of handling concurrent requests with minimal RAM footprint (~40 MB).
- **Progressive Web App (PWA):** Standalone installable application with offline app-shell caching via Service Worker.
- **Real-Time SSE Streaming:** Low-latency token generation using HTTP Server-Sent Events (`text/event-stream`).
- **Bilingual Dialogue Engine:** Native English and Bengali (বাংলা) support across all personas and quick prompts.
- **Persona Modes:** Specialized system instructions for General, Coding, Writing, and Learning contexts.
- **Enterprise Security Guardrails:** In-memory sliding-window rate limiting (30 req/min), 1 MB payload limits, path traversal blocking, and zero hardcoded secrets.
- **Triple-Theme UI:** Futuristic Dark Theme, high-contrast Light Theme, and auto-detecting System Theme.

---

## Architecture & Folder Structure

```text
Personal-AI-Chatbot/
├── src/com/nexora/
│   ├── controller/          # HTTP request handlers & static asset server
│   ├── service/             # AI service integrations (Ollama & OpenRouter)
│   ├── server/              # AppConfig, RateLimiter, NexoraHttpServer
│   ├── util/                # Zero-dependency SimpleJson & PersonaPrompts
│   └── Main.java            # Server bootstrap & process lifecycle
├── frontend/                # Vanilla HTML5, modern CSS3 tokens & ES6 JavaScript
│   ├── css/nexora.css       # Complete responsive design & theme engine
│   ├── js/app.js            # State management, Markdown parser, SSE reader
│   ├── assets/              # SVG brand icons & screenshots
│   ├── manifest.json        # PWA configuration
│   └── sw.js                # Service Worker cache rules
├── screenshots/             # Responsive desktop and mobile application captures
├── Dockerfile               # Production multi-stage Alpine build
├── render.yaml              # Render.com Infrastructure-as-Code blueprint
├── build.bat / build.sh     # Native platform compilation scripts
├── run.bat / run.sh         # Native platform local execution scripts
├── run-public.bat           # Native Windows launcher for cloud public mode
└── tests/                   # Optional Windows developer verification test scripts
```

---

## Technical Specifications

| Component | Implementation Detail |
| :--- | :--- |
| **Language** | Java 21 LTS (OpenJDK / Eclipse Temurin) |
| **Concurrency** | `Executors.newVirtualThreadPerTaskExecutor()` |
| **HTTP Engine** | Built-in `com.sun.net.httpserver.HttpServer` |
| **HTTP Client** | Built-in `java.net.http.HttpClient` (HTTP/2 capable) |
| **JSON Parser** | Handcrafted zero-dependency recursive descent parser (`SimpleJson`) |
| **Frontend** | Vanilla ES6 JavaScript, HTML5, CSS custom properties |
| **Local Model** | Ollama with `qwen2.5:7b` (32k context support) |
| **Public Provider** | OpenRouter Free Tier (`meta-llama/llama-3.2-3b-instruct:free`) |

---

## Quickstart Guide

### Prerequisites
- **Java 21 LTS** or higher installed and on `PATH`.
- *(Optional for Local Mode)* **Ollama** running locally with `qwen2.5:7b` installed:
  ```bash
  ollama pull qwen2.5:7b
  ollama serve
  ```

### 1. Compile the Backend
On Windows:
```cmd
build.bat
```
On Linux / macOS:
```bash
chmod +x build.sh run.sh
./build.sh
```

### 2. Run the Application
**Local Mode (Default):**
```cmd
run.bat
```
Access the application in your browser at: `http://127.0.0.1:8080/`

**Public Mode (Cloud Provider):**
```cmd
set OPENROUTER_API_KEY=your_key_here
run-public.bat
```

---

## Environment Configuration

| Variable | Default Value | Description |
| :--- | :--- | :--- |
| `PORT` | `8080` | HTTP port for server binding |
| `APP_MODE` | `LOCAL` | Operating mode: `LOCAL` or `PUBLIC` |
| `FRONTEND_DIR` | `frontend` | Directory path containing static assets |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` | Base endpoint for local Ollama |
| `OLLAMA_DEFAULT_MODEL` | `qwen2.5:7b` | Default local model |
| `OPENROUTER_API_KEY` | *(empty)* | API key for public mode completions |
| `OPENROUTER_DEFAULT_MODEL` | `meta-llama/llama-3.2-3b-instruct:free` | Default model in public mode |
| `RATE_LIMIT_PER_MINUTE` | `30` | Sliding window rate limit per client IP |
| `MAX_REQUEST_SIZE_BYTES` | `1048576` (1 MB) | Maximum accepted JSON request size |

---

## Docker Deployment

Build and run NEXORA locally using Docker:

```bash
# Build multi-stage image (~120 MB)
docker build -t nexora:latest .

# Run container
docker run -d -p 8080:8080 \
  -e APP_MODE=PUBLIC \
  -e OPENROUTER_API_KEY=your_api_key \
  nexora:latest
```

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
