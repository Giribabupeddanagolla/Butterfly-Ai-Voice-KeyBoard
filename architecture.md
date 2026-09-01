# ARCHITECTURE.md

## Multilingual AI Agent — System Architecture

**Version:** 1.0
**Date:** August 26, 2026
**Project:** Multilingual AI Agent
**Architecture Type:** Client–Server + AI Service + Persistent Memory

---

# 1. Architecture Overview

The Multilingual AI Agent uses a layered architecture.

```text
┌──────────────────────────────────────────────┐
│                  FRONTEND                    │
│                                              │
│        HTML + CSS + JavaScript               │
│                                              │
│  Chat UI │ Microphone │ Audio │ Sessions     │
└──────────────────────┬───────────────────────┘
                       │
                 HTTP / REST API
                       │
                       ▼
┌──────────────────────────────────────────────┐
│                  BACKEND                     │
│                                              │
│                   FastAPI                    │
│                    app.py                    │
│                                              │
│  Chat │ STT │ TTS │ Conversation │ Health   │
└──────────────┬───────────────┬───────────────┘
               │               │
               ▼               ▼
┌──────────────────────┐  ┌───────────────────┐
│      AI AGENT        │  │   VOICE SERVICES  │
│    ai_agent.py       │  │                   │
│                      │  │ Speech-to-Text    │
│ Project Context      │  │ Text-to-Speech    │
│ Session History      │  │                   │
└──────────┬───────────┘  └───────────────────┘
           │
     ┌─────┴──────────┐
     ▼                ▼
┌──────────────┐  ┌────────────────┐
│ Project      │  │ Persistent     │
│ Context      │  │ Memory         │
│              │  │                │
│ conversation_│  │ SQLite         │
│ context.py   │  │ memory.py      │
└──────────────┘  └────────────────┘
```

---

# 2. Architecture Goals

The architecture must:

* Support multilingual communication.
* Support voice and text.
* Preserve the user's language.
* Maintain conversation context.
* Store conversations persistently.
* Separate frontend and backend.
* Protect API credentials.
* Be easy to maintain.
* Be easy to extend.
* Support future AI tools.
* Support future database migration.

---

# 3. High-Level Architecture

```text
                    USER
                     │
          ┌──────────┴──────────┐
          │                     │
       TEXT INPUT          VOICE INPUT
          │                     │
          │                     ▼
          │                Microphone
          │                     │
          │                     ▼
          │                Audio Upload
          │                     │
          └──────────┬──────────┘
                     ▼
                FRONTEND
             HTML/CSS/JavaScript
                     │
                     │ REST API
                     ▼
                  FASTAPI
                  app.py
                     │
          ┌──────────┼──────────┐
          │          │          │
          ▼          ▼          ▼
       AI Agent     STT        TTS
          │
          ▼
   Context + Memory
          │
     ┌────┴─────┐
     ▼          ▼
 Project      SQLite
 Context      Memory
     │          │
     └────┬─────┘
          ▼
       AI MODEL
          │
          ▼
     AI RESPONSE
          │
     ┌────┴─────┐
     ▼          ▼
    TEXT       AUDIO
     │          │
     └────┬─────┘
          ▼
         USER
```

---

# 4. Project Structure

```text
multilingual-ai-agent/
│
├── backend/
│   ├── app.py
│   ├── ai_agent.py
│   ├── conversation_context.py
│   ├── memory.py
│   ├── speech_to_text.py
│   ├── text_to_speech.py
│   ├── config.py
│   ├── requirements.txt
│   ├── .env
│   │
│   └── audio/
│       ├── input/
│       └── output/
│
├── frontend/
│   ├── index.html
│   ├── style.css
│   └── script.js
│
├── data/
│   └── conversations.db
│
└── README.md
```

---

# 5. Layered Architecture

The application consists of six logical layers.

```text
┌──────────────────────────────┐
│  1. Presentation Layer       │
│  HTML / CSS / JavaScript     │
├──────────────────────────────┤
│  2. API Layer                │
│  FastAPI                     │
├──────────────────────────────┤
│  3. AI Agent Layer           │
│  AI + Context + History      │
├──────────────────────────────┤
│  4. Voice Layer              │
│  STT + TTS                   │
├──────────────────────────────┤
│  5. Memory Layer             │
│  Session + Persistent Memory │
├──────────────────────────────┤
│  6. Storage Layer            │
│  SQLite + Audio Files        │
└──────────────────────────────┘
```

---

# 6. Presentation Layer

## Technology

```text
HTML5
CSS3
JavaScript
```

## Responsibilities

The frontend handles:

* Chat display.
* Text input.
* Send button.
* Microphone.
* Recording state.
* Audio playback.
* Loading state.
* Conversation list.
* Session selection.

The frontend must not contain the OpenAI API key.

---

# 7. API Layer

## File

```text
backend/app.py
```

FastAPI acts as the communication layer between the frontend and backend services.

## Responsibilities

* Receive HTTP requests.
* Validate requests.
* Call appropriate services.
* Return JSON responses.
* Handle exceptions.
* Configure CORS.

## Main APIs

```text
GET  /health

POST /chat

POST /speech-to-text

POST /text-to-speech

GET  /conversation/{session_id}

DELETE /conversation/{session_id}
```

---

# 8. AI Agent Layer

## File

```text
backend/ai_agent.py
```

This is the central intelligence layer.

Responsibilities:

* Receive user messages.
* Load project context.
* Load conversation history.
* Send messages to the AI model.
* Generate responses.
* Preserve user language.
* Save messages to memory.

Architecture:

```text
User Message
     │
     ▼
AI Agent
     │
     ├── Project Context
     │
     ├── Session History
     │
     └── Persistent Memory
     │
     ▼
AI Model
     │
     ▼
AI Response
```

---

# 9. Project Context Layer

## File

```text
backend/conversation_context.py
```

This contains permanent instructions for the AI Agent.

Example responsibilities:

```text
Project description
Technology stack
Architecture
Language rules
Existing features
Coding rules
Feature-development rules
```

The project context should be loaded for every AI request.

---

# 10. Language Preservation Architecture

The most important behavior is language preservation.

```text
User Input
    │
    ▼
Language Detection / Recognition
    │
    ▼
Language = Telugu
    │
    ▼
AI Agent
    │
    ▼
Response in Telugu
```

For English:

```text
English Input
     ↓
English
     ↓
AI
     ↓
English Response
```

For Hindi:

```text
Hindi Input
     ↓
Hindi
     ↓
AI
     ↓
Hindi Response
```

The system should not translate the user's message into English unless translation is explicitly requested.

---

# 11. Voice Architecture

The voice subsystem has two components.

```text
┌─────────────────────┐
│ Speech-to-Text      │
│ speech_to_text.py   │
└──────────┬──────────┘
           │
           ▼
      Text Message
           │
           ▼
        AI Agent
           │
           ▼
      AI Response
           │
           ▼
┌─────────────────────┐
│ Text-to-Speech      │
│ text_to_speech.py   │
└──────────┬──────────┘
           │
           ▼
        Audio
```

---

# 12. Speech-to-Text Architecture

## File

```text
backend/speech_to_text.py
```

Flow:

```text
Microphone
    ↓
Browser Audio
    ↓
POST /speech-to-text
    ↓
FastAPI
    ↓
Speech-to-Text Service
    ↓
Text + Language
    ↓
Frontend
```

The resulting text is then sent to the AI Agent.

---

# 13. Text-to-Speech Architecture

## File

```text
backend/text_to_speech.py
```

Flow:

```text
AI Response
     ↓
Language
     ↓
Text-to-Speech Service
     ↓
Audio File
     ↓
Frontend
     ↓
Audio Player
```

---

# 14. Memory Architecture

The system contains three types of memory.

```text
                 MEMORY
                    │
       ┌────────────┼────────────┐
       ▼            ▼            ▼
 Project Memory  Session      Persistent
                 Memory        Memory
       │            │            │
       ▼            ▼            ▼
conversation_  conversation_  SQLite
context.py     history        database
```

---

# 15. Project Memory

Project memory is static.

```text
conversation_context.py
```

It contains information that should remain available to the AI Agent.

Example:

```text
Project Name
Project Purpose
Technology Stack
Features
Architecture
Development Rules
Language Requirements
```

---

# 16. Session Memory

Session memory stores the current conversation.

Example:

```text
User:
Create login page.

AI:
I'll create the login page.

User:
Add Google login.

AI:
I'll add Google login to the existing page.
```

The session history allows the AI to understand references to earlier messages.

---

# 17. Persistent Memory

Persistent memory is stored in:

```text
data/conversations.db
```

The database stores conversation messages.

Suggested structure:

```text
messages
───────────────
id
session_id
role
content
language
created_at
```

---

# 18. Memory Request Flow

```text
User Message
     │
     ▼
Get Session ID
     │
     ▼
Load Previous Messages
     │
     ▼
Load Project Context
     │
     ▼
Send Context + History + Message
     │
     ▼
AI Model
     │
     ▼
AI Response
     │
     ▼
Save User Message
     │
     ▼
Save AI Response
     │
     ▼
Return Response
```

---

# 19. Database Architecture

Initial database:

```text
SQLite
```

Location:

```text
data/conversations.db
```

Recommended logical schema:

```text
┌─────────────────────────────┐
│          messages           │
├─────────────────────────────┤
│ id                          │
│ session_id                  │
│ role                        │
│ content                     │
│ language                    │
│ created_at                  │
└─────────────────────────────┘
```

The `session_id` should be indexed for fast conversation retrieval.

---

# 20. Audio Storage Architecture

Audio directories:

```text
backend/
└── audio/
    ├── input/
    └── output/
```

Input:

```text
User audio
```

Output:

```text
AI generated audio
```

Temporary audio files should be cleaned periodically.

---

# 21. Configuration Architecture

## File

```text
backend/config.py
```

Environment configuration:

```text
.env
```

Example:

```text
OPENAI_API_KEY=your_api_key
AI_MODEL=your_available_model
STT_MODEL=your_available_stt_model
TTS_MODEL=your_available_tts_model
DATABASE_PATH=../data/conversations.db
```

Secrets must never be stored in frontend files.

---

# 22. Request Flow — Text

```text
┌──────────┐
│   User   │
└────┬─────┘
     │
     │ Text
     ▼
┌──────────────┐
│   Frontend   │
└────┬─────────┘
     │
     │ POST /chat
     ▼
┌──────────────┐
│   FastAPI    │
└────┬─────────┘
     │
     ▼
┌──────────────┐
│   AI Agent   │
└────┬─────────┘
     │
     ├── Context
     ├── History
     └── Memory
     │
     ▼
┌──────────────┐
│   AI Model   │
└────┬─────────┘
     │
     ▼
┌──────────────┐
│   Response   │
└────┬─────────┘
     │
     ▼
┌──────────────┐
│   Frontend   │
└──────────────┘
```

---

# 23. Request Flow — Voice

```text
User
 │
 │ Speak
 ▼
Microphone
 │
 ▼
Frontend
 │
 │ Audio
 ▼
FastAPI
 │
 ▼
Speech-to-Text
 │
 ▼
Text + Language
 │
 ▼
AI Agent
 │
 ├── Project Context
 ├── Session History
 └── Persistent Memory
 │
 ▼
AI Model
 │
 ▼
AI Response
 │
 ▼
Text-to-Speech
 │
 ▼
Audio
 │
 ▼
Frontend
 │
 ▼
User
```

---

# 24. Error Architecture

Errors should be handled at each layer.

```text
Frontend Error
      ↓
API Error
      ↓
Service Error
      ↓
AI / STT / TTS Error
      ↓
Database Error
```

The backend should return consistent error responses.

Example:

```json
{
  "success": false,
  "error": "Unable to process your request."
}
```

---

# 25. Security Architecture

```text
             Security
                 │
     ┌───────────┼───────────┐
     ▼           ▼           ▼
   API Key     Input       Audio
 Protection   Validation   Validation
     │           │           │
     └───────────┼───────────┘
                 ▼
          Secure Backend
```

Requirements:

* API keys stored in `.env`.
* API keys never sent to browser.
* Validate request payloads.
* Validate audio files.
* Limit upload size.
* Restrict CORS in production.
* Do not log secrets.
* Protect persistent conversation data.

---

# 26. Scalability Architecture

The initial architecture:

```text
Frontend
   ↓
FastAPI
   ↓
AI API
   ↓
SQLite
```

Future production architecture:

```text
                    Load Balancer
                         │
                ┌────────┴────────┐
                ▼                 ▼
             FastAPI           FastAPI
             Server 1          Server 2
                │                 │
                └────────┬────────┘
                         ▼
                    AI Service
                         │
                         ▼
                    PostgreSQL
                         │
                         ▼
                    File Storage
```

SQLite is suitable for the initial application, while PostgreSQL can be introduced for larger multi-user deployments.

---

# 27. Future AI Tool Architecture

The architecture should allow tools to be added later.

```text
                       AI AGENT
                           │
          ┌────────────────┼────────────────┐
          ▼                ▼                ▼
       AI Model         Memory             Tools
                                            │
                              ┌─────────────┼─────────────┐
                              ▼             ▼             ▼
                           Web Search     Files        APIs
```

Possible future tools:

* Web search.
* File processing.
* PDF processing.
* Image analysis.
* Code execution.
* External APIs.
* Database tools.
* Automation.

---

# 28. Authentication Architecture — Future

Future version:

```text
User
 ↓
Login
 ↓
Authentication
 ↓
Session
 ↓
AI Agent
 ↓
User-specific Memory
```

Each user should have isolated conversations and memory.

---

# 29. Real-Time Voice Architecture — Future

Current architecture:

```text
Record
 ↓
Upload
 ↓
STT
 ↓
AI
 ↓
TTS
 ↓
Play
```

Future real-time architecture:

```text
User
  ↕
Real-Time Audio
  ↕
Voice AI
  ↕
AI Model
  ↕
Memory
```

This can provide a more natural voice conversation experience.

---

# 30. Deployment Architecture

Development:

```text
Browser
   ↓
localhost
   ↓
FastAPI
   ↓
OpenAI API
   ↓
SQLite
```

Production:

```text
Browser
   ↓
HTTPS
   ↓
Frontend Hosting
   ↓
FastAPI Backend
   ↓
AI Service
   ↓
Production Database
```

---

# 31. Component Responsibilities

| Component                 | Responsibility            |
| ------------------------- | ------------------------- |
| `index.html`              | UI structure              |
| `style.css`               | UI styling                |
| `script.js`               | Frontend behavior         |
| `app.py`                  | API server                |
| `ai_agent.py`             | AI processing             |
| `conversation_context.py` | Permanent project context |
| `memory.py`               | Conversation storage      |
| `speech_to_text.py`       | Voice transcription       |
| `text_to_speech.py`       | Voice generation          |
| `config.py`               | Configuration             |
| `conversations.db`        | Persistent memory         |

---

# 32. Architecture Principles

## Separation of Concerns

Each module should have one primary responsibility.

## Language Preservation

User language must be preserved throughout the AI flow.

## Security

Secrets must remain on the backend.

## Extensibility

New AI tools and services should be easy to add.

## Persistence

Conversation data should survive server restarts.

## Maintainability

The project should use clear modules and predictable responsibilities.

---

# 33. Final Architecture

```text
                         ┌───────────────┐
                         │     USER      │
                         └───────┬───────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
                 TEXT INPUT              VOICE INPUT
                    │                         │
                    │                    MICROPHONE
                    │                         │
                    │                         ▼
                    │                    SPEECH-TO-TEXT
                    │                         │
                    └────────────┬────────────┘
                                 ▼
                         ┌───────────────┐
                         │   FRONTEND    │
                         │ HTML/CSS/JS   │
                         └───────┬───────┘
                                 │
                              REST API
                                 │
                                 ▼
                         ┌───────────────┐
                         │    FASTAPI    │
                         │    app.py     │
                         └───────┬───────┘
                                 │
                         ┌───────▼───────┐
                         │   AI AGENT    │
                         │ ai_agent.py   │
                         └───────┬───────┘
                                 │
              ┌──────────────────┼──────────────────┐
              │                  │                  │
              ▼                  ▼                  ▼
      Project Context     Session History    Persistent Memory
     conversation_        conversation_       SQLite Database
     context.py            history             memory.py
              │                  │                  │
              └──────────────────┼──────────────────┘
                                 ▼
                           ┌────────────┐
                           │ AI MODEL   │
                           └─────┬──────┘
                                 │
                                 ▼
                           AI RESPONSE
                                 │
                         ┌───────┴───────┐
                         │               │
                        TEXT             TTS
                         │               │
                         │               ▼
                         │            AUDIO
                         │               │
                         └───────┬───────┘
                                 ▼
                              USER
```

---

# 34. Architecture Summary

The Multilingual AI Agent follows a **modular client-server architecture**.

The frontend manages user interaction, FastAPI manages API communication, the AI Agent manages reasoning and project context, voice modules manage speech processing, and the memory layer manages conversation persistence.

The most important architectural principle is:

```text
User Language
      ↓
Speech/Text
      ↓
Language Preserved
      ↓
Project Context
      +
Conversation History
      +
Persistent Memory
      ↓
AI Model
      ↓
Same-Language Response
      ↓
Text + Optional Voice
```

This architecture provides a strong foundation for the MVP while keeping the system ready for **long-term memory, authentication, real-time voice, web search, file processing, AI tools, and production-scale deployment**.
