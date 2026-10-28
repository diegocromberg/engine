#!/usr/bin/env python3
"""
V28 - Generación en tanda: lee la KB UNA sola vez y genera varias TRN.

  python3 gx2yaml_lote.py OptimistU12NEW.xml --trns A,B,C --salida ../src/main/resources/trn --cache kb_todas.json
  python3 gx2yaml_lote.py OptimistU12NEW.xml --un-nivel --salida ...      (todas las de un solo nivel)
  python3 gx2yaml_lote.py OptimistU12NEW.xml --dos-niveles --salida ...   (cabecera + uno o varios detalles)
  --saltear A,B   no toca esas (ej. las que ya tienen retoques a mano)
  --no-pisar      no genera las que ya tienen YAML en la carpeta de salida

Deja un informe (_informe_lote.txt) con lo generado, lo salteado y los errores.
"""
import argparse, json, os, sys, traceback
import gx2yaml as g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('--trns', default='')
    ap.add_argument('--un-nivel', action='store_true')
    ap.add_argument('--dos-niveles', action='store_true')
    ap.add_argument('--saltear', default='')
    ap.add_argument('--no-pisar', action='store_true')
    ap.add_argument('--salida', default='.')
    ap.add_argument('--cache')
    a = ap.parse_args()

    kb = None
    if a.cache and os.path.exists(a.cache):
        kb = json.load(open(a.cache, encoding='utf-8'))
        if kb.get('_version') != 7 or kb.get('_trn') != '*':
            kb = None
    if kb is None:
        print('Leyendo la KB completa (una sola vez)...', flush=True)
        kb = g.leer_kb(a.xml, {'*'})
        kb['_trn'] = '*'
        if a.cache:
            json.dump(kb, open(a.cache, 'w', encoding='utf-8'), ensure_ascii=False)

    if a.un_nivel:
        lista = sorted(n for n, t in kb['trns'].items()
                       if len(t['niveles']) == 1 and not t['niveles'][0]['niveles'])
    elif a.dos_niveles:
        lista = sorted(n for n, t in kb['trns'].items()
                       if len(t['niveles']) == 1 and t['niveles'][0]['niveles']
                       and not any(s['niveles'] for s in t['niveles'][0]['niveles']))
    else:
        lista = [x.strip() for x in a.trns.split(',') if x.strip()]
    saltear = {x.strip().lower() for x in a.saltear.split(',') if x.strip()}
    os.makedirs(a.salida, exist_ok=True)

    hechos, salteados, errores = [], [], []
    M = g._modelo(kb)
    for n in lista:
        destino = os.path.join(a.salida, n + '.yaml')
        if n.lower() in saltear or (a.no_pisar and os.path.exists(destino)):
            salteados.append(n)
            continue
        if n not in kb['trns'] or n not in kb['objetivos']:
            errores.append((n, 'no está en la KB'))
            continue
        claves = [x['n'] for x in kb['trns'][n]['niveles'][0]['atts'] if x['key']]
        raras = [c for c in claves if M.tipo(c)[1] is None]
        if raras:
            errores.append((n, 'la clave ' + ', '.join(raras) + ' es de un tipo que el motor todavía no maneja (GUID/Blob)'))
            continue
        if not claves:
            errores.append((n, 'no tiene clave (en GeneXus no es una transacción de datos)'))
            continue
        try:
            y = g.generar(kb, n, set())
            open(destino, 'w', encoding='utf-8').write(y)
            hechos.append(n)
        except Exception as e:
            errores.append((n, f'{type(e).__name__}: {e}'))
    with open(os.path.join(a.salida, '_informe_lote.txt'), 'w', encoding='utf-8') as f:
        f.write(f'Generadas ({len(hechos)}):\n  ' + '\n  '.join(hechos) + '\n')
        f.write(f'Salteadas ({len(salteados)}):\n  ' + '\n  '.join(salteados) + '\n')
        f.write(f'Con error ({len(errores)}):\n' + ''.join(f'  {n}: {e}\n' for n, e in errores))
    print(f'Generadas {len(hechos)}, salteadas {len(salteados)}, con error {len(errores)}')


if __name__ == '__main__':
    main()
