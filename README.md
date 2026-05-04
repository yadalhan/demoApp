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
- **Password encryption service** with AES
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
    └── PasswordService.java      # Password encryption service

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
spring.datasource.url=jdbc:postgresql://localhost:5432/demodb
spring.datasource.username=postgres
spring.datasource.password=password
```

For Vault integration:
```properties
spring.cloud.vault.uri=http://localhost:8200
spring.cloud.vault.token=your-vault-token
```

## Building the Project

### Using the provided build script:
```bash
./build-with-env.sh
```

### Using Gradle wrapper:
```bash
# Build the project
./gradlew clean build

# Run tests
./gradlew test

# Run the application
./gradlew bootRun
```

### Using specified environment:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=/opt/gradle/gradle-8.7/bin:$PATH
./gradlew clean build
```

## Running the Application

1. **Build the JAR:**
   ```bash
   ./gradlew clean build
   ```

2. **Run the JAR:**
   ```bash
   java -jar build/libs/xaandemo-0.0.2-SNAPSHOT.jar
   ```

3. **Access the application:**
   - Main page: http://localhost:8080
   - API endpoints: http://localhost:8080/api/boards

## API Endpoints

### Board Management
- `GET /api/boards` - Get all boards
- `GET /api/boards/{id}` - Get board by ID
- `POST /api/boards` - Create new board
- `PUT /api/boards/{id}` - Update board
- `DELETE /api/boards/{id}` - Delete board

### Pagination
- `GET /api/boards/page` - Get paginated boards (first page)
- `GET /api/boards/page/first-only` - Get first page only

## Database Schema

The application uses the following main entity:

```sql
CREATE TABLE board (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    author VARCHAR(100),
    password VARCHAR(255),
    created_date TIMESTAMP,
    modified_date TIMESTAMP
);
```

## Security Features

1. **Password Encryption**: AES encryption for sensitive data
2. **Vault Integration**: External secrets management
3. **Input Validation**: Server-side validation
4. **SQL Injection Protection**: Using JPA prepared statements

## Deployment

### Docker (Example)
```dockerfile
FROM openjdk:17-jdk-slim
COPY build/libs/xaandemo-0.0.2-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

### Traditional Deployment
1. Build the JAR: `./gradlew clean build`
2. Copy JAR to server: `scp build/libs/*.jar user@server:/app/`
3. Run with: `java -jar xaandemo-0.0.2-SNAPSHOT.jar`

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

## License

This project is available for use under the MIT License.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## Support

For issues and feature requests, please use the GitHub Issues page.