# =============================================================================
# Wayang Platform - Complete Deployment Configuration
# =============================================================================

# Maven Dependencies (pom.xml)
---
dependencies:
  # Quarkus Core
  - groupId: io.quarkus
    artifactId: quarkus-rest-jackson
  - groupId: io.quarkus
    artifactId: quarkus-hibernate-reactive-panache
  - groupId: io.quarkus
    artifactId: quarkus-reactive-pg-client
  
  # Secret Management
  - groupId: io.quarkus
    artifactId: quarkus-vault
    version: 3.6.0
  
  # AWS SDK
  - groupId: software.amazon.awssdk
    artifactId: secretsmanager
    version: 2.20.0
  - groupId: software.amazon.awssdk
    artifactId: sts
    version: 2.20.0
  
  # Azure SDK
  - groupId: com.azure
    artifactId: azure-security-keyvault-secrets
    version: 4.7.0
  - groupId: com.azure
    artifactId: azure-identity
    version: 1.11.0
  
  # Security & Crypto
  - groupId: org.bouncycastle
    artifactId: bcprov-jdk18on
    version: 1.77
  
  # Jackson
  - groupId: com.fasterxml.jackson.core
    artifactId: jackson-databind
  
  # Scheduler
  - groupId: io.quarkus
    artifactId: quarkus-scheduler
  
  # Testing
  - groupId: io.quarkus
    artifactId: quarkus-junit5
    scope: test
  - groupId: io.rest-assured
    artifactId: rest-assured
    scope: test
  - groupId: org.testcontainers
    artifactId: testcontainers
    version: 1.19.3
    scope: test
  - groupId: org.testcontainers
    artifactId: postgresql
    version: 1.19.3
    scope: test
  - groupId: org.testcontainers
    artifactId: localstack
    version: 1.19.3
    scope: test

---
# =============================================================================
# Application Properties
# =============================================================================

# application.properties - Development
---
# Quarkus Configuration
quarkus.application.name: wayang-platform
quarkus.http.port: 8080

# Database
quarkus.datasource.db-kind: postgresql
quarkus.datasource.username: wayang
quarkus.datasource.password: ${DB_PASSWORD:wayang}
quarkus.datasource.reactive.url: postgresql://localhost:5432/wayang
quarkus.hibernate-orm.database.generation: update

# Secret Backend Selection
secret.backend: local  # Options: local, vault, aws, azure

# Local Encrypted Secret Manager
secret.master-key: ${SECRET_MASTER_KEY}  # 32-byte base64 key
secret.retention-days: 30

# HashiCorp Vault (when secret.backend=vault)
quarkus.vault.url: http://localhost:8200
quarkus.vault.authentication.client-token: ${VAULT_TOKEN}
quarkus.vault.kv-secret-engine-version: 2
quarkus.vault.kv-secret-engine-mount-path: secret
quarkus.vault.connect-timeout: 5S
quarkus.vault.read-timeout: 1S
vault.enable-audit: true
vault.secret.mount-path: secret

# AWS Secrets Manager (when secret.backend=aws)
quarkus.secretsmanager.region: us-east-1
quarkus.secretsmanager.endpoint-override: ${AWS_ENDPOINT:}
aws.secrets.prefix: wayang/
aws.secrets.kms-key-id: ${AWS_KMS_KEY_ID:}

# Azure Key Vault (when secret.backend=azure)
azure.keyvault.vault-url: ${AZURE_VAULT_URL}
azure.keyvault.tenant-id: ${AZURE_TENANT_ID}
azure.keyvault.secret-prefix: wayang-

# API Key Service
apikey.hash.secret: ${APIKEY_HASH_SECRET}  # 32-byte base64
apikey.rate-limit.requests-per-minute: 60

# Logging
quarkus.log.level: INFO
quarkus.log.category."tech.kayys.wayang".level: DEBUG
quarkus.log.console.format: "%d{HH:mm:ss} %-5p [%c{2.}] (%t) %s%e%n"

# CORS (for development)
quarkus.http.cors: true
quarkus.http.cors.origins: http://localhost:3000
quarkus.http.cors.methods: GET,POST,PUT,DELETE,PATCH

---
# application.properties - Production
---
# Production-specific overrides
quarkus.log.level: INFO
quarkus.http.cors: false

# Database - Production
quarkus.datasource.username: ${DB_USERNAME}
quarkus.datasource.password: ${DB_PASSWORD}
quarkus.datasource.reactive.url: ${DB_URL}
quarkus.datasource.reactive.max-size: 20

# Secret Backend - Production (use Vault or cloud provider)
secret.backend: vault

# Vault Production
quarkus.vault.url: ${VAULT_URL}
quarkus.vault.authentication.app-role.role-id: ${VAULT_ROLE_ID}
quarkus.vault.authentication.app-role.secret-id: ${VAULT_SECRET_ID}

# Security
quarkus.http.ssl-port: 8443
quarkus.http.insecure-requests: disabled

# Metrics & Health
quarkus.micrometer.enabled: true
quarkus.micrometer.export.prometheus.enabled: true
quarkus.smallrye-health.ui.enable: true

---
# =============================================================================
# Docker Compose - Development Environment
# =============================================================================
---
version: '3.8'

services:
  # PostgreSQL
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_USER: wayang
      POSTGRES_PASSWORD: wayang
      POSTGRES_DB: wayang
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U wayang"]
      interval: 10s
      timeout: 5s
      retries: 5

  # HashiCorp Vault
  vault:
    image: hashicorp/vault:1.15
    ports:
      - "8200:8200"
    environment:
      VAULT_DEV_ROOT_TOKEN_ID: dev-token
      VAULT_DEV_LISTEN_ADDRESS: 0.0.0.0:8200
    cap_add:
      - IPC_LOCK
    command: server -dev

  # LocalStack (AWS Services)
  localstack:
    image: localstack/localstack:latest
    ports:
      - "4566:4566"
    environment:
      SERVICES: secretsmanager,kms
      DEBUG: 1
      DATA_DIR: /tmp/localstack/data
    volumes:
      - localstack_data:/tmp/localstack

  # Wayang Platform
  wayang:
    build: .
    ports:
      - "8080:8080"
    environment:
      DB_PASSWORD: wayang
      VAULT_TOKEN: dev-token
      SECRET_BACKEND: vault
      QUARKUS_DATASOURCE_REACTIVE_URL: postgresql://postgres:5432/wayang
      QUARKUS_VAULT_URL: http://vault:8200
      SECRET_MASTER_KEY: ${SECRET_MASTER_KEY}
      APIKEY_HASH_SECRET: ${APIKEY_HASH_SECRET}
    depends_on:
      postgres:
        condition: service_healthy
      vault:
        condition: service_started
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/q/health"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  postgres_data:
  localstack_data:

---
# =============================================================================
# Kubernetes Deployment
# =============================================================================
---
apiVersion: v1
kind: Namespace
metadata:
  name: wayang

---
apiVersion: v1
kind: Secret
metadata:
  name: wayang-secrets
  namespace: wayang
type: Opaque
stringData:
  db-password: changeme
  vault-token: changeme
  secret-master-key: changeme
  apikey-hash-secret: changeme

---
apiVersion: v1
kind: ConfigMap
metadata:
  name: wayang-config
  namespace: wayang
data:
  application.properties: |
    quarkus.datasource.reactive.url=postgresql://postgres:5432/wayang
    quarkus.vault.url=http://vault:8200
    secret.backend=vault

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: wayang-platform
  namespace: wayang
spec:
  replicas: 3
  selector:
    matchLabels:
      app: wayang-platform
  template:
    metadata:
      labels:
        app: wayang-platform
    spec:
      containers:
      - name: wayang
        image: wayang/platform:latest
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: wayang-secrets
              key: db-password
        - name: VAULT_TOKEN
          valueFrom:
            secretKeyRef:
              name: wayang-secrets
              key: vault-token
        - name: SECRET_MASTER_KEY
          valueFrom:
            secretKeyRef:
              name: wayang-secrets
              key: secret-master-key
        - name: APIKEY_HASH_SECRET
          valueFrom:
            secretKeyRef:
              name: wayang-secrets
              key: apikey-hash-secret
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5

---
apiVersion: v1
kind: Service
metadata:
  name: wayang-platform
  namespace: wayang
spec:
  selector:
    app: wayang-platform
  ports:
  - port: 80
    targetPort: 8080
  type: LoadBalancer

---
# =============================================================================
# Testing Configuration
# =============================================================================
---
# JUnit Tests

# SecretManagerTest.java
test:
  secret-manager:
    backends:
      - local
      - vault
      - aws
      - azure
    scenarios:
      - store-and-retrieve
      - rotation
      - deletion
      - versioning
      - expiration
      - caching

# APIKeyServiceTest.java
test:
  apikey-service:
    scenarios:
      - create-key
      - validate-key
      - revoke-key
      - rotate-key
      - rate-limiting
      - scope-validation

# SecretInjectionTest.java
test:
  secret-injection:
    scenarios:
      - field-injection
      - cache-invalidation
      - rotation-refresh
      - multi-tenant

---
# =============================================================================
# Environment Variables Reference
# =============================================================================
---
# Required for all environments
SECRET_MASTER_KEY: "Base64-encoded 32-byte key for local encryption"
APIKEY_HASH_SECRET: "Base64-encoded 32-byte key for API key hashing"
DB_PASSWORD: "PostgreSQL password"

# Vault-specific
VAULT_URL: "https://vault.example.com"
VAULT_TOKEN: "Vault authentication token (dev only)"
VAULT_ROLE_ID: "AppRole role ID (production)"
VAULT_SECRET_ID: "AppRole secret ID (production)"

# AWS-specific
AWS_ACCESS_KEY_ID: "AWS access key (if not using IAM roles)"
AWS_SECRET_ACCESS_KEY: "AWS secret key (if not using IAM roles)"
AWS_KMS_KEY_ID: "KMS key ID for encryption"
AWS_ENDPOINT: "LocalStack endpoint (dev only)"

# Azure-specific
AZURE_VAULT_URL: "https://<vault-name>.vault.azure.net/"
AZURE_TENANT_ID: "Azure tenant ID"
AZURE_CLIENT_ID: "Managed identity client ID"

---
# =============================================================================
# Secret Generation Commands
# =============================================================================
---
# Generate master encryption key (32 bytes)
generate-master-key: |
  openssl rand -base64 32

# Generate API key hash secret (32 bytes)
generate-apikey-secret: |
  openssl rand -base64 32

# Initialize Vault (development)
init-vault: |
  export VAULT_ADDR=http://localhost:8200
  export VAULT_TOKEN=dev-token
  vault secrets enable -path=secret kv-v2
  vault kv put secret/wayang/test key=value

# Test secret storage
test-vault: |
  vault kv put secret/tenants/test-tenant/api-key api_key=test123
  vault kv get secret/tenants/test-tenant/api-key

---
# =============================================================================
# CI/CD Pipeline (GitHub Actions)
# =============================================================================
---
name: Wayang Platform CI/CD

on:
  push:
    branches: [ main, develop ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_PASSWORD: wayang
          POSTGRES_USER: wayang
          POSTGRES_DB: wayang
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
    
    - name: Generate test secrets
      run: |
        echo "SECRET_MASTER_KEY=$(openssl rand -base64 32)" >> $GITHUB_ENV
        echo "APIKEY_HASH_SECRET=$(openssl rand -base64 32)" >> $GITHUB_ENV
    
    - name: Run tests
      run: ./mvnw clean verify
    
    - name: Build native image
      run: ./mvnw package -Pnative
    
    - name: Upload artifact
      uses: actions/upload-artifact@v3
      with:
        name: wayang-platform
        path: target/*-runner

  deploy:
    needs: test
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    
    steps:
    - name: Deploy to production
      run: |
        # Deploy to Kubernetes/Cloud
        echo "Deploying to production..."