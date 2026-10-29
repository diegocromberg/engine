#!/usr/bin/env python3
"""Arma OPTIMIST-Designer.html (un solo archivo, funciona sin internet) a partir de disenador.src.html,
la librería yaml (github.com/eemeli/yaml, licencia ISC) empaquetada en yaml.bundle.js y el importador de GeneXus
(importador.js, E2) y la plantilla de "Nueva aplicación" (../../plantilla-aplicacion, E4)."""
import json, pathlib, re
aca = pathlib.Path(__file__).parent
src = (aca / 'disenador.src.html').read_text(encoding='utf-8')
lib = (aca / 'yaml.bundle.js').read_text(encoding='utf-8').replace('</script', '<\\/script')
imp = (aca / 'importador.js').read_text(encoding='utf-8').replace('</script', '<\\/script')
src = src.replace('/*YAML_LIB*/', '/* yaml 2.9.1 (c) Eemeli Aro, ISC */\n' + lib, 1)
src = src.replace('/*IMPORTADOR*/', '/* OPTIMIST Engine: importador de GeneXus (importador.js) */\n' + imp, 1)
# E4: la plantilla de aplicación ({ruta: contenido}) y la versión del motor (la del pom.xml)
raiz = aca.parent.parent
plantilla = {p.relative_to(raiz / 'plantilla-aplicacion').as_posix(): p.read_text(encoding='utf-8')
             for p in sorted((raiz / 'plantilla-aplicacion').rglob('*')) if p.is_file()}
version = re.search(r'<artifactId>optimist-engine</artifactId>\s*<version>([^<]+)</version>', (raiz / 'pom.xml').read_text(encoding='utf-8')).group(1)
src = src.replace('/*PLANTILLA*/null', json.dumps(plantilla, ensure_ascii=False).replace('</', '<\\/'), 1)
src = src.replace("/*VERSION_MOTOR*/''", json.dumps(version), 1)
(aca / 'OPTIMIST-Designer.html').write_text(src, encoding='utf-8')
print('OPTIMIST-Designer.html listo')
