# {{TITULO}}

Aplicación de **OPTIMIST Engine**: TRN, menú y web panels definidos en YAML (`src/main/resources`).

## Cómo se arranca
1. El motor tiene que estar instalado en esta PC: en la carpeta `engine`, `mvn install` (una vez por cada versión del motor).
2. Copiar `optimist.cfg.ejemplo` a `C:/optimist/config/{{NOMBRE}}/optimist.cfg` y completar la base, el usuario y la contraseña.
3. En esta carpeta:
   ```
   mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port={{PUERTO}} --optimist.config=C:/optimist/config/{{NOMBRE}}/optimist.cfg"
   ```
4. Entrar a http://localhost:{{PUERTO}} con un usuario de `{{U_TABLA}}`.

## Armar el WAR para el servidor
```
mvn clean package -Pproduction
```
Queda en `target/{{NOMBRE}}.war`.
