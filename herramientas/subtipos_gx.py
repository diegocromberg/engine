#!/usr/bin/env python3
"""
V48 - Grupos de subtipos de GeneXus -> src/main/resources/subtipos.yaml

    python3 subtipos_gx.py OptimistU12NEW.xml --salida ..\\src\\main\\resources\\subtipos.yaml

Los usa el intérprete de procedimientos para la TABLA EXTENDIDA de los For Each, como GeneXus: en el grupo STCOBR_Tcbte,
COBRCPI / COBRClaseCbte / COBRTipoCbte son la clave de Tcbte (TcbteCPI / TcbteClaseCbte / TcbteTipoCbte), así que
COBRCOBRPAGOImputacion se lee de Tcbte.TcbteCOBRPAGOModoUso uniendo COBR con Tcbte por esos tres.
Formato: grupo: {subtipo: supertipo, ...}. Se regenera cuando cambian los subtipos en la KB.
"""
import argparse, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gx2yaml as gy


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('--salida', required=True)
    a = ap.parse_args()
    kb = gy.leer_kb(a.xml, '*')
    grupos = {}
    for sub, info in kb['subs'].items():
        for g in info.get('grupos') or [info.get('grupo')]:
            if g and info.get('super'):
                grupos.setdefault(g, {})[sub] = info['super']
    out = ['# Grupos de subtipos de GeneXus (generado con herramientas/subtipos_gx.py desde la KB). No editar a mano.',
           '# grupo: {subtipo: supertipo}. Los usa el intérprete para la tabla extendida de los For Each.']
    for g in sorted(grupos, key=str.lower):
        out.append(f'{g}: {{' + ', '.join(f'{s}: {p}' for s, p in sorted(grupos[g].items(), key=lambda x: x[0].lower())) + '}')
    open(a.salida, 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print(f'{len(grupos)} grupos, {sum(len(v) for v in grupos.values())} subtipos -> {a.salida}')


if __name__ == '__main__':
    main()
