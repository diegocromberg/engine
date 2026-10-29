# OPTIMIST Engine — Manual del desarrollador

Versión del manual: **E4** (29/10/2026). Se actualiza con cada versión del motor (ver `CAMBIOS.md`).
El detalle de cada YAML está en `GUIA_CONFIGURACION_TRN.md` (la "guía"); este manual es el camino de punta a punta.
Los dos se leen desde el Designer con **❓ Ayuda** (o F1), que abre en el tema de lo que se está haciendo.

---

## 1. Qué es

OPTIMIST Engine ejecuta aplicaciones definidas en archivos YAML: transacciones (TRN) con su WorkWith y sus lupas, web panels,
procedimientos escritos en lenguaje GeneXus, consultas, impresiones y menú. Las TRN se importan de una KB de GeneXus con
**OPTIMIST Designer**, que también sirve para retocarlas a mano (formulario, WorkWith, propiedades).

Una aplicación tiene:
- el **motor** (`optimist-engine.jar`), igual para todas;
- sus **YAML** (`src/main/resources`), lo que la hace distinta;
- opcionalmente, **Java propio** enchufado al motor (extensiones, sección 9) para lo que no se resuelve con YAML.

El resultado es un WAR que corre en Tomcat, o solo con `java -jar`.

## 2. Requisitos

| Qué | Versión | Para qué |
|---|---|---|
| Java (JDK) | 25 | compilar y ejecutar |
| Maven | 3.9 o posterior | armar el motor y las aplicaciones |
| MySQL | 5.6 o posterior (o MariaDB) | la base de la aplicación. *SQL Server: en preparación* |
| Tomcat | 11 (opcional) | servidor de producción; también corre sin Tomcat |
| Chrome o Edge | actual | OPTIMIST Designer |

La base es la misma que usaba la aplicación GeneXus: el motor lee y graba sus tablas tal como están.

## 3. Instalar el motor

En la carpeta del motor (`engine`):

```
mvn install
```

Deja `optimist-engine.jar` en el repositorio Maven de la PC. Se hace **una vez por cada versión del motor** que se baja; las
aplicaciones lo toman de ahí.

Carpetas recomendadas, una al lado de la otra:

```
OPTIMIST\
  engine\          el motor y el Designer
  mi-aplicacion\   cada aplicación en su carpeta (y su repositorio Git)
```

## 4. Crear una aplicación

1. Abrir `engine/herramientas/disenador/OPTIMIST-Designer.html` en Chrome o Edge.
2. **✨ Nueva aplicación**: carpeta (ej. `mi-aplicacion`, también es el nombre del WAR), título, grupo Maven (ej. `com.miempresa`),
   puerto para probar, nombre de la base y de dónde salen los usuarios (la tabla `SecUser` de WorkWithPlus u otra).
3. Elegir dónde crearla (la carpeta `OPTIMIST`, al lado de `engine`).
4. Al terminar ofrece **importar las TRN de GeneXus** (sección 5).

Queda armada con: `pom.xml`, `src/main/resources/` (`menu.yaml`, `dominios.yaml`, `seguridad.yaml`, `trn/`,
`application.properties`), `optimist.cfg.ejemplo`, `README.md` y `CAMBIOS.md`.

## 5. Importar de GeneXus

1. En GeneXus: **Knowledge Manager → Export** de las transacciones con sus instancias de WorkWithPlus (XML o XPZ).
2. En el Designer: **⬇ Importar de GeneXus** → elegir el archivo exportado y la carpeta de la aplicación.
3. La lista muestra cada TRN con su estado: *nueva*, *ya migrada*, *cambió en GeneXus* o *no se puede*. Tildar las que se
   quieren migrar (👁 muestra el formulario y el WorkWith antes de importar). Avisa las TRN que usan otras todavía no migradas.
4. **Importar**: deja `trn/<TRN>.yaml` y, por cada una, `trn/_gx/<TRN>.gx.txt` con lo que GeneXus tenía y el motor no tradujo
   (reglas y eventos especiales). Ese texto está pensado para pasárselo a la IA que use cada uno y terminar la traducción.
5. Agregar las TRN al `menu.yaml`:
   ```yaml
   Mi Aplicación:
     - {titulo: Clientes, trn: Clientes}
     - Tablas:
         - {titulo: Países, trn: Paises}
   ```

Volver a importar una TRN ya migrada **pisa** lo que se haya cambiado a mano (pide confirmación y deja un `.bak`).

Probado con GeneXus 17 + WorkWithPlus. *GeneXus 15, 16, 18, Evolution y K2BTools: en validación.*

## 6. Arrancar la aplicación en la PC

1. Copiar `optimist.cfg.ejemplo` **afuera** del proyecto (ej. `C:/optimist/config/mi-aplicacion/optimist.cfg`) y completar:
   ```
   database.host=localhost
   database.port=3306
   database.name=MiBase
   database.user=usuario
   database.password=clave
   sistema.nombre=Mi Aplicación
   ```
2. En la carpeta de la aplicación:
   ```
   mvn spring-boot:run "-Dspring-boot.run.arguments=--server.port=18082 --optimist.config=C:/optimist/config/mi-aplicacion/optimist.cfg"
   ```
3. Entrar a `http://localhost:18082` con un usuario de la tabla de `seguridad.yaml`.

La primera vez tarda más (Vaadin arma la pantalla). Mientras corre, **un cambio en un YAML se ve al reabrir la pantalla**, sin
reiniciar.

## 7. Usuarios

`src/main/resources/seguridad.yaml` dice dónde están los usuarios (guía, 24.1):

```yaml
usuarios:
  tabla: SecUser
  id: SecUserId
  nombre: SecUserName
  clave: SecUserPassword
  tipoClave: texto      # texto | md5 | sha1 | sha256
```

En los YAML el usuario conectado es `&SecUserId` / `&SecUserName`. Si la KB usa **GAM** u otro sistema, el ingreso se resuelve con
una extensión (sección 9).

## 8. Armar y publicar el WAR

```
mvn clean package -Pproduction
```

- Antes de armarlo **se validan todos los YAML**; si hay errores, el WAR no se genera y Maven los lista.
- Queda en `target/mi-aplicacion.war`. Se copia a `webapps` de Tomcat 11, o se ejecuta con `java -jar mi-aplicacion.war`.
- En el servidor, la configuración va en `/usr/optimist/<nombre del WAR>/optimist.cfg` (o donde diga `-Doptimist.config`).

Opciones útiles del `optimist.cfg` (guía para el resto):

| Clave | Qué hace |
|---|---|
| `sistema.nombre` | nombre que muestra la pantalla |
| `sistema.postits` / `sistema.mensajes` | prende o apaga esos módulos (guía 24.2) |
| `application.precalentar` / `application.precalentarTrn` | deja armadas las TRN al arrancar |
| `application.<Condicion>=true` | condiciones de `visibleSi` de los YAML |
| `totales.usuarios` | quién ve el botón Σ de totales (`*` = todos) |
| `mail.host`, `mail.puerto`, `mail.usuario`, `mail.clave`… | servidor de correo de los procedimientos que mandan mails (guía 16.1) |

## 9. Java propio: extensiones

Lo que no se resuelve con YAML se enchufa en Java, sin tocar el motor. En la aplicación:

1. Una clase que implementa `ar.com.datafix.optimist.OptimistExtension` (en `src/main/java`):
   ```java
   package com.miempresa;

   import ar.com.datafix.optimist.*;
   import java.sql.*;
   import java.util.*;

   public class MiExtension implements OptimistExtension {
       @Override public List<String> variablesContexto() { return List.of("Sucursal"); }

       @Override public void alIniciarSesion(Connection cn, String usuario, OptimistContexto ctx) throws SQLException {
           try (PreparedStatement p = cn.prepareStatement("SELECT SucursalId FROM Usuarios WHERE Nombre=?")) {
               p.setString(1, usuario);
               try (ResultSet r = p.executeQuery()) { if (r.next()) ctx.poner("Sucursal", r.getObject(1)); }
           }
       }
   }
   ```
   Con eso, los YAML pueden usar `"&Sucursal"` (ej. `default: "&Sucursal"` o en el filtro del WorkWith).
2. Anotarla en `src/main/resources/META-INF/services/ar.com.datafix.optimist.OptimistExtension` (un renglón por clase):
   ```
   com.miempresa.MiExtension
   ```

Todos los enchufes disponibles están en la guía, sección 23: validar el ingreso, variables de contexto, procedimientos en Java,
parámetros globales, procesos habilitados por usuario, módulos, cálculos propios de las consultas, etc. Si una extensión anotada
no se puede cargar, la aplicación no arranca (para no quedar andando a medias).

## 10. Actualizar el motor

1. Bajar la versión nueva del motor (en la carpeta `engine`).
2. `mvn install` en `engine`.
3. Si el `CAMBIOS.md` del motor lo pide, cambiar `optimist.engine.version` en el `pom.xml` de la aplicación.
4. Volver a arrancar la aplicación (o armar el WAR).

## 11. Problemas comunes

| Síntoma | Causa |
|---|---|
| Maven: `Could not find artifact ar.com.datafix:optimist-engine` | falta `mvn install` en `engine` (sección 3) |
| "Falta configurar los usuarios de la aplicación" | falta `src/main/resources/seguridad.yaml` |
| "No encontré …optimist.cfg" | la ruta de `--optimist.config` no existe |
| Una TRN no abre y muestra un error de YAML | el mensaje dice archivo y motivo; corregir y reabrir la pantalla |
| Un cambio en un YAML no se ve | la aplicación corre desde otra carpeta: arrancarla desde la carpeta del proyecto |
