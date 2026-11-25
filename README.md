# NEXORA — Personal AI Chatbot

**A multilingual, privacy-conscious personal AI assistant powered by Java 21, Ollama, and OpenRouter.**

NEXORA is a personal AI chatbot designed to provide a clean conversational experience with local and cloud-based AI model support. It combines a Java-based backend with a lightweight web interface built using HTML, CSS, and vanilla JavaScript.

## Overview

NEXORA brings together local AI capabilities, cloud model access, multilingual conversations, and personal chat features in one application. It is designed to support Bengali, Banglish, English, and multilingual interactions.

## Features

- **Multilingual Chat:** Communicate in Bengali, Banglish, English, and supported languages.
- **Local AI Support:** Connect to locally installed Ollama models.
- **Cloud AI Support:** Use the OpenRouter Free model router.
- **Dynamic Model Center:** Discover, manage, select, and switch supported AI models.
- **Personal Memory:** Maintain conversational context and memory features.
- **Per-Chat Model Selection:** Choose a model for an individual conversation.
- **Streaming Responses:** Receive AI responses through server-sent events.
- **Chat History:** Access previous conversations.
- **Export:** Export conversations for personal use.
- **Installable PWA:** Use the web application through a supported browser's installation feature.

## Technology Stack

| Component       | Technology                      |
| --------------- | ------------------------------- |
| Backend         | Java 21                         |
| HTTP Server     | Java `HttpServer`               |
| Concurrency     | Java Virtual Threads            |
| Frontend        | HTML5, CSS3, Vanilla JavaScript |
| Local AI        | Ollama                          |
| Cloud AI        | OpenRouter                      |
| Web App         | Progressive Web App (PWA)       |
| Version Control | Git and GitHub                  |

## AI Model Support

### Local Models

- Qwen 2.5 3B
- Qwen 2.5 7B

### Cloud Models

- OpenRouter Free (`openrouter/free`)

Cloud model availability and request limits depend on the upstream provider.

## Project Architecture

NEXORA uses a Java 21 backend to handle HTTP requests, AI communication, model management, and application services. The frontend communicates with the backend through HTTP APIs and server-sent events.

## Run Locally

**Prerequisites**

- Java 21 or later
- Ollama (optional, for local AI models)
- OpenRouter API key (for cloud AI access)

**Steps**

1. Clone the repository.
2. Configure your local environment using the provided `.env.example`.
3. Add your private API key to your local `.env` file if using OpenRouter.
4. Install the required Ollama models if you want local AI support.
5. Build and start NEXORA using the project's supplied scripts.
6. Open `http://127.0.0.1:8080` in your browser.

Refer to the deployment documentation for the exact environment variables and production setup.

## Configuration

Never commit private credentials or API keys.

- Keep `.env` local and private.
- Use `.env.example` as a template containing placeholders only.
- Configure production secrets through your hosting provider's environment settings.
- Do not expose server-side API keys in frontend JavaScript.

## Security and Privacy

NEXORA includes public-mode security protections intended to prevent public users from accessing local-only model management and server-side private memory operations.

Before public deployment, verify authentication, user isolation, request limits, and persistent storage requirements for the chosen hosting environment.

## Deployment

Deployment documentation: [`DEPLOYMENT.md`](DEPLOYMENT.md)

The public deployment uses a Java-compatible hosting environment and a configured cloud AI provider. The deployed service requires HTTPS for secure public access and PWA installation.

## Known Limitations

- Free cloud AI providers may enforce request quotas and rate limits.
- Free hosting may sleep during inactivity and have slower cold starts.
- Ephemeral hosting storage is not suitable for permanent user data.
- Local Ollama models run on the machine hosting Ollama; they are not automatically available to public users.
- PWA installation does not make AI inference available offline.

## Future Improvements

- Improved multi-user account and privacy management.
- More robust persistent storage.
- Additional AI provider integrations.
- Enhanced monitoring and deployment automation.
- Further accessibility and user-experience improvements.

## License

No open-source license has been selected yet. All rights remain with the project owner unless a license is added.

---

**NEXORA — Your Personal AI, Your Conversational Space.**
