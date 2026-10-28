#!/usr/bin/env python3
"""Arma OPTIMIST-Designer.html (un solo archivo, funciona sin internet) a partir de disenador.src.html
y la librería yaml (github.com/eemeli/yaml, licencia ISC) empaquetada en yaml.bundle.js."""
import pathlib
aca = pathlib.Path(__file__).parent
src = (aca / 'disenador.src.html').read_text(encoding='utf-8')
lib = (aca / 'yaml.bundle.js').read_text(encoding='utf-8').replace('</script', '<\\/script')
(aca / 'OPTIMIST-Designer.html').write_text(src.replace('/*YAML_LIB*/', '/* yaml 2.9.1 (c) Eemeli Aro, ISC */\n' + lib, 1), encoding='utf-8')
print('OPTIMIST-Designer.html listo')
