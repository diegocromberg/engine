#!/usr/bin/env python3
"""
E4e - Arma optimist-python.js: el Python que corre adentro del Designer (Pyodide, licencia MPL 2.0), embebido para que
funcione sin internet y abriendo el Designer como archivo (file://, donde el navegador no deja leer otros archivos).
Se arma una sola vez por versión de Pyodide (no cambia con las herramientas: esas las mete armar.py en el HTML).

  npm pack pyodide@0.27.7 && tar xzf pyodide-0.27.7.tgz
  python3 armar_python.py package/
"""
import base64, json, pathlib, sys

aca = pathlib.Path(__file__).parent
pyo = pathlib.Path(sys.argv[1])
archivos = ['pyodide.asm.wasm', 'python_stdlib.zip', 'pyodide-lock.json', 'pyodide.asm.js', 'pyodide.js']
datos = {n: base64.b64encode((pyo / n).read_bytes()).decode() for n in archivos}
version = json.loads((pyo / 'package.json').read_text())['version']
(aca / 'optimist-python.js').write_text(
    f'/* OPTIMIST Designer: Python {version} para el importador de GeneXus (Pyodide, MPL 2.0, pyodide.org). Generado por armar_python.py */\n'
    'window.__PYO=' + json.dumps(datos) + ';\n', encoding='utf-8')
print('optimist-python.js listo (Pyodide', version + ')')
