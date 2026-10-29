#!/usr/bin/env python3
"""Arma OPTIMIST-Designer.html (un solo archivo, funciona sin internet) a partir de disenador.src.html,
la librería yaml (github.com/eemeli/yaml, licencia ISC) empaquetada en yaml.bundle.js y el importador de GeneXus
(importador.js, E2) la plantilla de "Nueva aplicación" (../../plantilla-aplicacion, E4) y la ayuda: el manual y la guía del motor
(con marked, github.com/markedjs/marked, licencia MIT, en marked.umd.js).
E4e: las herramientas de Python del importador (../*.py) y PyYAML (pyyaml/, licencia MIT), en un zip; corren con Pyodide
(optimist-python.js, aparte: armar_python.py)."""
import base64, io, json, pathlib, re, zipfile
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
# E4: la ayuda (los .md del motor) y marked para mostrarlos
mk = (aca / 'marked.umd.js').read_text(encoding='utf-8').replace('</script', '<\\/script')
src = src.replace('/*MARKED*/', '/* marked 15.0.12 (c) MarkedJS, MIT */\n' + mk, 1)
docs = {'manual': (raiz / 'MANUAL_DESARROLLADOR.md').read_text(encoding='utf-8'), 'guia': (raiz / 'GUIA_CONFIGURACION_TRN.md').read_text(encoding='utf-8')}
src = src.replace('/*DOCS*/null', json.dumps(docs, ensure_ascii=False).replace('</', '<\\/'), 1)
z = io.BytesIO()
with zipfile.ZipFile(z, 'w', zipfile.ZIP_DEFLATED) as zz:
    for f in sorted(aca.parent.glob('*.py')):
        if f.name not in ('chequear_java.py', 'gx2yaml_lote.py'):
            zz.write(f, f.name)
    for f in sorted((aca / 'pyyaml' / 'yaml').glob('*.py')):
        zz.write(f, 'pyyaml/yaml/' + f.name)
src = src.replace('/*HERRAMIENTAS_PY*/null', json.dumps(base64.b64encode(z.getvalue()).decode()), 1)
(aca / 'OPTIMIST-Designer.html').write_text(src, encoding='utf-8')
print('OPTIMIST-Designer.html listo')
