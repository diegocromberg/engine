#!/usr/bin/env python3
"""
V66l - Las clases del tema de GeneXus (WorkWithPlusTheme) -> src/main/resources/clases_gx.yaml.

Para los colores que en GeneXus salen de una clase guardada en un parámetro (ej. ParamGlb 177 ClassTitleClienteDebe =
"AttributeBoldRed") o escrita en el código (&Saldo.Class = "AttributeTitleGreen"): el motor busca la clase en este
archivo y pinta la celda con su color, fondo, negrita, itálica y subrayado. El tamaño y la letra no se toman (manda la
del usuario).

  python3 gx2clases.py OptimistU12NEW.xml --salida ../src/main/resources/clases_gx.yaml [--tema WorkWithPlusTheme]
"""
import argparse, re


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('xml')
    ap.add_argument('--salida', required=True)
    ap.add_argument('--tema', default='WorkWithPlusTheme')
    a = ap.parse_args()
    dentro, clases, actual = False, {}, None
    with open(a.xml, encoding='utf-8') as f:
        for linea in f:
            if '<Object ' in linea:
                if dentro: break
                dentro = f'name="{a.tema}"' in linea
                continue
            if not dentro: continue
            m = re.search(r'<GxClass Name="([^"]+)"', linea)
            if m:
                actual = clases.setdefault(m.group(1), {})
                continue
            if '</GxClass>' in linea:
                actual = None
                continue
            if actual is None: continue
            m = re.search(r'<(color|background_color|font_weight|font_style|text_decoration)>([^<]*)</', linea)
            if m and m.group(2).strip(): actual.setdefault(m.group(1), m.group(2).strip())
    util = {}
    for n, p in clases.items():
        e = {}
        if p.get('color'): e['color'] = p['color']
        if p.get('background_color') and p['background_color'].lower() not in ('transparent', 'white', '#ffffff', '#fff'): e['fondo'] = p['background_color']
        if p.get('font_weight') in ('bold', 'bolder', '700', '800', '900'): e['negrita'] = True
        if p.get('font_style') == 'italic': e['italica'] = True
        if p.get('text_decoration') in ('underline',): e['subrayado'] = True
        if e: util[n] = e
    with open(a.salida, 'w', encoding='utf-8') as o:
        o.write('# Clases del tema de GeneXus (' + a.tema + ') - GENERADO con herramientas/gx2clases.py (V66l)\n')
        o.write('# El motor pinta con estos datos las celdas cuya clase sale de un parámetro (ParamGlb) o de la consulta.\n')
        for n in sorted(util, key=str.lower):
            e = util[n]
            o.write(n + ': {' + ', '.join(f'{k}: "{v}"' if isinstance(v, str) else f'{k}: true' for k, v in e.items()) + '}\n')
    print(len(util), 'clases ->', a.salida)


if __name__ == '__main__':
    main()
