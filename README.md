# demoApp - Spring Boot Application

A Spring Boot demo application with comprehensive features for board/article management.

## Features

- **Spring Boot 3.4.0** with Java 17
- **PostgreSQL** database integration
- **JPA with Hibernate** for data persistence
- **Thymeleaf** templating engine for server-side rendering
- **Board/Article management system** with CRUD operations
- **Vault configuration** for secrets management
- **Gradle build system** with wrapper
- **Spring Cloud Vault** integration
- **Password encryption service** - Using `vault-crypto` package (separate JAR)
- **Pagination support** for board listings

## Project Structure

```
src/main/java/com/xaan/demo/
├── DemoApplication.java          # Main application entry point
├── config/
│   └── VaultConfig.java          # Vault configuration
├── controller/
│   ├── BoardApiController.java   # REST API controller
│   ├── IndexController.java      # Main page controller
│   └── Top100IndexController.java # Top 100 listings controller
├── domain/
│   ├── entity/
│   │   ├── BaseTimeEntity.java   # Base entity with timestamps
│   │   └── Board.java            # Board entity
│   └── repository/
│       └── BoardRepository.java  # JPA repository
├── dto/
│   ├── BoardResponseDto.java     # Response DTO
│   ├── BoardSaveRequestDto.java  # Save request DTO
│   └── BoardUpdateRequestDto.java # Update request DTO
└── service/
    ├── BoardService.java         # Business logic service
    └── PasswordService.java      # Password encryption service (uses vault-crypto)

src/main/resources/
├── application.properties        # Application configuration
└── templates/                    # Thymeleaf templates
    ├── index.html               # Main page
    ├── list1st.html             # First page listing
    ├── list1stonly.html         # First page only listing
    └── posts/                   # Post-related templates
        ├── save.html            # Save post form
        └── update.html          # Update post form
```

## Prerequisites

- Java 17 or higher
- Gradle 8.7 or higher
- PostgreSQL database (optional - can use H2 for development)
- HashiCorp Vault (optional - for production secrets management)

## Configuration

The application uses the following configuration in `application.properties`:

```properties
spring.application.name=demo
spring.jpa.database=postgresql
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/limadb?currentSchema=${DB_SCHEMA:ebiz}
spring.datasource.username=${DB_USERNAME:postgres}
spring.datasource.password=${DB_PASSWORD:changeme}
```

### Vault Configuration (Updated 2026-05-07)

The application now uses **HashiCorp Vault kv-v2** (versioned key-value) backend:

```properties
# Vault Configuration (connection only)
spring.cloud.vault.uri=http://192.168.2.57:8200
spring.cloud.vault.token=${VAULT_TOKEN}
spring.cloud.vault.fail-fast=false
```

### Vault kv-v2 Integration
- **Mount**: `ebiz_service` (kv-v2)
- **Secret Path**: `ebiz_db/data-enc-key`
- **Key**: `fernet-key` (32 bytes, used as AES-256 key)
- **Server**: 192.168.2.57:8200

### How It Works
1. **vault-crypto package** (`com.xaan:vault-crypto:0.0.1`) provides encryption
2. `PasswordService` delegates to `VaultCryptoService` for encrypt/decrypt
3. Vault key is read at startup and used for AES-256 (GCM mode)
4. Passwords stored as Base64-encoded encrypted strings in DB
5. Python decryption script available for verification (`decrypt_passwords.py`)

### vault-crypto Package
Encryption functionality is extracted into a separate package:
- **Location**: `/home/xaan/opencode/projects/vault-crypto/`
- **Build**: `./gradlew build publishToMavenLocal`
- **Usage**: See `vault-crypto/README.md` for details

### Setting up Vault Secrets
```bash
# Enable kv-v2 backend (if not already enabled)
vault secrets enable -path=ebiz_service kv-v2

# Store the Fernet encryption key
vault kv put -mount=ebiz_service ebiz_db/data-enc-key \
  fernet-key="NgqOBievnB9500cQOnSQ-cmbBx38KnOiKx5ooQ_e97Y=" \
  description="encryption key for ebiz db column"

# Verify the secret
vault kv get -mount=ebiz_service ebiz_db/data-enc-key
```

> **Note**: Vault secret path `ebiz_service/data/ebiz_db/data-enc-key` needs to be configured. 
> See [VAULT_AND_ENCRYPTION.md](VAULT_AND_ENCRYPTION.md) for implementation details.

## Building the Project

> **⚠️ Build Environment Notes:**
> - **JAVA_HOME**: `/usr/lib/jvm/java-17-openjdk-amd64` (Required: Project needs Java 17, system default is Java 8)
> - **GRADLE_HOME**: `/opt/gradle/gradle-8.7` (Optional: Project uses Gradle wrapper `./gradlew`)
> - Always set JAVA_HOME before building, or use the provided scripts that handle this automatically.

### Using the provided build script (Recommended):
```bash
# This script automatically sets JAVA_HOME and PATH
./build-with-env.sh
```

### Using Gradle wrapper:
```bash
# Set environment variables first
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:$PATH
export PATH=/opt/gradle/gradle-8.7/bin:$PATH

# Build the project
./gradlew clean build

# Run tests
./gradlew test

# Run the application
./gradlew bootRun
```

### Quick build with environment:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$JAVA_HOME/bin:/opt/gradle/gradle-8.7/bin:$PATH
./gradlew clean build
```

## Running the Application

1. **Build the JAR:**
   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
   export PATH=$JAVA_HOME/bin:$PATH
   ./gradlew clean build
   ```

2. **Run the JAR:**
   ```bash
   java -jar build/libs/xaandemo-0.0.3.jar
   ```

3. **Access the application:**
   - Main page: http://localhost:8080
   - API endpoints: http://localhost:8080/api/v1/posts
   - Last 100: http://localhost:8080/last100

## API Endpoints

### Board Management (REST API)
- `GET /api/v1/posts` - Get all posts (405 for GET, use POST to create)
- `GET /api/v1/posts/{id}` - Get post by ID
- `POST /api/v1/posts` - Create new post (with optional password)
- `PUT /api/v1/posts/{id}` - Update post (with optional password)
- `DELETE /api/v1/posts/{id}` - Delete post

### Web Pages
- `GET /` - Main page (index)
- `GET /posts/save` - Post creation form
- `GET /posts/update/{id}` - Post update form
- `GET /last100` - Last 100 posts listing
- `GET /list1st` - First page listing
- `GET /list1stonly` - First page only listing

### Pagination
- `GET /api/boards/page` - Get paginated boards (first page)
- `GET /api/boards/page/first-only` - Get first page only

## Database Schema

The application uses the following main entity in schema `ebiz`:

```sql
CREATE TABLE ebiz.board (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    author VARCHAR(100),
    password VARCHAR(255), -- AES encrypted (Base64 encoded)
    created_date TIMESTAMP,
    modified_date TIMESTAMP
);
```

> **Password Storage**: Passwords are encrypted using `vault-crypto` package (AES-256/GCM/NoPadding, authenticated encryption).
> See [VAULT_AND_ENCRYPTION.md](VAULT_AND_ENCRYPTION.md) for implementation details.
> Python decryption script (`decrypt_passwords.py`) available for verification.
> vault-crypto package: `/home/xaan/opencode/projects/vault-crypto/README.md`

## Security Features

1. **Password Encryption**: AES-256 encryption via `vault-crypto` package
   - Implementation: `PasswordService.java` uses `VaultCryptoService`
   - Encryption key from Vault kv-v2 (32-byte Fernet key as AES-256 key)
    - Package: `com.xaan:vault-crypto:0.0.1`
   - See [VAULT_AND_ENCRYPTION.md](VAULT_AND_ENCRYPTION.md) for details
   - Python decryption script available for testing (`decrypt_passwords.py`)
   - ✅ **Production tested** (2026-05-08): Encryption/decryption verified

2. **Vault Integration**: External secrets management with Spring Cloud Vault
2. **Vault Integration**: External secrets management with Spring Cloud Vault
   - Configured to connect to Vault server at `http://192.168.2.57:8200`
   - Fail-fast disabled to allow startup without Vault
3. **Input Validation**: Server-side validation
4. **SQL Injection Protection**: Using JPA prepared statements

## Deployment

### Using deploy.sh (Recommended)
The project includes a deployment script for production server (192.168.2.57):

```bash
./deploy.sh
```

This script will:
1. Build the application with Java 17
2. Distribute the JAR to production server
3. Stop the running application
4. Start the new version
5. Verify the deployment

### Docker (Example)
```dockerfile
FROM openjdk:17-jdk-slim
COPY build/libs/xaandemo-0.0.3.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Traditional Deployment
1. Build the JAR: `./gradlew clean build`
2. Copy JAR to server: `scp build/libs/xaandemo-0.0.3.jar user@server:/app/`
3. Run with: `java -jar xaandemo-0.0.3.jar`

### Production Server Details
- **Host**: 192.168.2.57
- **User**: xaan
- **App Directory**: `/home/xaan/ws/demoBBS/app`
- **Application URL**: http://192.168.2.57:8080

## Development

### Code Style
- Follows Spring Boot conventions
- Uses Lombok for boilerplate reduction
- JPA auditing with `@EnableJpaAuditing`
- DTO pattern for API requests/responses

### Testing
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "*DemoApplicationTests"
```

## Release History

### v0.0.3 (2026-05-08)

**Security & Refactoring Release** - Password encryption service refactored to use `vault-crypto` package.

**Security Improvements:**
- Password encryption implemented using `vault-crypto` package (AES-256 GCM, NoPadding)
- Encryption key loaded from HashiCorp Vault kv-v2 backend at startup
- Old plain-text passwords migrated to encrypted format
- Python decryption script (`decrypt_passwords.py`) for verification
- ✅ Production tested (2026-05-08) with post ID 2017588

**Architecture:**
- `PasswordService.java` refactored to delegate to `VaultCryptoService`
- `vault-crypto:0.0.1` dependency introduced
- `PasswordService` now uses `@Service` annotation with `@Autowired VaultOperations`
- Vault connection configured with fail-fast disabled

**Bug Fixes:**
- Fixed Vault kv-v2 integration with proper Fernet key field (`fernet-key`)
- Fixed Vault key name: `fernet-key` instead of `data-enc-key`

**Documentation:**
- `VAULT_AND_ENCRYPTION.md` - Full implementation details
- `VAULT_INTEGRATION_DIAGRAM.md` - System architecture diagram
- Python decryption script for password verification

### v0.0.2 (2026-05-06)

**Vault Integration Release** - Initial integration with HashiCorp Vault for secrets management.

**Features:**
- Spring Cloud Vault integration
- Vault kv-v2 backend configuration
- Fail-fast disabled for graceful degradation

**Bug Fixes:**
- Fixed Vault URI and token configuration in `application.properties`
- Fixed password encryption in `BoardService.java` (injected PasswordService)
- Migrated old plain-text passwords to encrypted format

**Deployment:**
- Deploy script (`deploy.sh`) for production server
- Production URL: http://192.168.2.57:8080

### v0.0.1 (2026-05-06)

**Initial release** - Spring Boot demo application with board/article management.

**Features:**
- Spring Boot 3.4.0 with Java 17
- PostgreSQL database integration (schema: `ebiz.board`)
- JPA with Hibernate for data persistence
- Thymeleaf templating engine
- REST API (`/api/v1/posts`) and web pages
- Gradle build system with wrapper
- Pagination support for board listings

This project is available for use under the MIT License.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## Support

For issues and feature requests, please use the GitHub Issues page.