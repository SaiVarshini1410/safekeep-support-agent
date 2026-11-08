#!/bin/bash
# Starts the SafeKeep Support Agent with demo API keys. Ollama must be running (ollama serve).
export BACKUP_SERVER_API_KEY=${BACKUP_SERVER_API_KEY:-demo-backup-key}
export STORAGE_SERVER_API_KEY=${STORAGE_SERVER_API_KEY:-demo-storage-key}
./mvnw spring-boot:run
