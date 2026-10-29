/* ============================================================================================
   OPTIMIST Engine · Importador de GeneXus (E2)
   Pasaje fiel a JavaScript de herramientas/gx2yaml.py (la referencia): lee el export XML o XPZ de una KB
   de GeneXus (X en adelante) y genera el YAML de cada TRN para el motor. Sin librerías.
   - Lee el XML por partes (stream): sirve para KB de cientos de MB.
   - El XPZ es un zip: se descomprime con DecompressionStream (Chrome / Edge / Node 18+).
   - Corre igual en el Designer (navegador) y en Node (pruebas: comparar contra gx2yaml.py).
   Cualquier cambio de criterio va PRIMERO en gx2yaml.py y después acá, y se comparan las salidas.
   ============================================================================================ */
const GXI = (() => {
'use strict';

const T_DOM = '00972a17-9975-449e-aab1-d26165d51393';
const T_TAB = '857ca50e-7905-0000-0007-c5d9ff2975ec';
const T_TRN = '1db606f2-af09-4cf9-a3b5-b481519d28f6';
const T_SUB = '87313f43-5eb2-41d7-9b8c-e8d9f5bf9588';
const T_WP = 'c9584656-94b6-4ccd-890f-332d11fc2c25';
const T_INST = '07135890-56fc-489b-b408-063722fa9f7d';
const P_STRUCT = '264be5fb-1b28-4b25-a598-6ca900dd059f';
const P_WEBFORM = 'd24a58ad-57ba-41b7-9e6e-eaca3543c778';
const P_RULES = '9b0a32a3-de6d-4be1-a4dd-1b85d3741534';
const P_CONDITIONS = '763f0d8b-d8ac-4db4-8dd4-de8979f2b5b9';
const P_EVENTS = 'c44bd5ff-f918-415b-98e6-aca44fed84fa';

// ----------------------------------------------------------------------------- utilidades (equivalentes de Python)

const ENT = { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'", nbsp: ' ', iexcl: '¡', cent: '¢', pound: '£', curren: '¤',
  yen: '¥', brvbar: '¦', sect: '§', uml: '¨', copy: '©', ordf: 'ª', laquo: '«', not: '¬', shy: '­', reg: '®', macr: '¯',
  deg: '°', plusmn: '±', sup2: '²', sup3: '³', acute: '´', micro: 'µ', para: '¶', middot: '·', cedil: '¸', sup1: '¹', ordm: 'º',
  raquo: '»', frac14: '¼', frac12: '½', frac34: '¾', iquest: '¿', Agrave: 'À', Aacute: 'Á', Acirc: 'Â', Atilde: 'Ã', Auml: 'Ä',
  Aring: 'Å', AElig: 'Æ', Ccedil: 'Ç', Egrave: 'È', Eacute: 'É', Ecirc: 'Ê', Euml: 'Ë', Igrave: 'Ì', Iacute: 'Í', Icirc: 'Î',
  Iuml: 'Ï', ETH: 'Ð', Ntilde: 'Ñ', Ograve: 'Ò', Oacute: 'Ó', Ocirc: 'Ô', Otilde: 'Õ', Ouml: 'Ö', times: '×', Oslash: 'Ø',
  Ugrave: 'Ù', Uacute: 'Ú', Ucirc: 'Û', Uuml: 'Ü', Yacute: 'Ý', THORN: 'Þ', szlig: 'ß', agrave: 'à', aacute: 'á', acirc: 'â',
  atilde: 'ã', auml: 'ä', aring: 'å', aelig: 'æ', ccedil: 'ç', egrave: 'è', eacute: 'é', ecirc: 'ê', euml: 'ë', igrave: 'ì',
  iacute: 'í', icirc: 'î', iuml: 'ï', eth: 'ð', ntilde: 'ñ', ograve: 'ò', oacute: 'ó', ocirc: 'ô', otilde: 'õ', ouml: 'ö',
  divide: '÷', oslash: 'ø', ugrave: 'ù', uacute: 'ú', ucirc: 'û', uuml: 'ü', yacute: 'ý', thorn: 'þ', yuml: 'ÿ',
  ndash: '–', mdash: '—', lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”', bull: '•', hellip: '…', euro: '€', trade: '™' };
const deNum = (n, base) => { const c = parseInt(n, base); return c > 0 && c <= 0x10ffff ? String.fromCodePoint(c) : '�'; };
/** html.unescape de Python (las entidades de uso real en GeneXus). */
function unescapeHtml(s) {
  if (s == null || s.indexOf('&') < 0) return s;
  return s.replace(/&(#[xX]([0-9a-fA-F]+)|#(\d+)|([A-Za-z][A-Za-z0-9]*));?/g, (m, _t, hx, dc, nom) => {
    if (hx) return deNum(hx, 16);
    if (dc) return deNum(dc, 10);
    if (Object.prototype.hasOwnProperty.call(ENT, nom) && m.endsWith(';')) return ENT[nom];
    return m;
  });
}
/** Las entidades de XML (para el texto y los atributos al leer el export). */
function unescapeXml(s) {
  if (s.indexOf('&') < 0) return s;
  return s.replace(/&(#[xX]([0-9a-fA-F]+)|#(\d+)|(lt|gt|amp|quot|apos));/g, (m, _t, hx, dc, nom) =>
    hx ? deNum(hx, 16) : dc ? deNum(dc, 10) : { lt: '<', gt: '>', amp: '&', quot: '"', apos: "'" }[nom]);
}
const strip = (s, ch) => {       // str.strip(chars) de Python
  if (ch == null) return s.trim();
  let i = 0, j = s.length;
  while (i < j && ch.includes(s[i])) i++;
  while (j > i && ch.includes(s[j - 1])) j--;
  return s.slice(i, j);
};
const splitN = (s, sep, n) => {   // str.split(sep, n) de Python
  const r = []; let x = s;
  while (r.length < n) { const i = x.indexOf(sep); if (i < 0) break; r.push(x.slice(0, i)); x = x.slice(i + sep.length); }
  r.push(x); return r;
};
const pySplit = s => s.split(/\s+/).filter(Boolean);   // str.split() sin argumentos
const isUpper = s => /\p{Lu}/u.test(s) && !/\p{Ll}/u.test(s);   // str.isupper()
const ljust = (s, n) => s.length >= n ? s : s + ' '.repeat(n - s.length);
const commonPrefix = (a, b) => { let i = 0; while (i < a.length && i < b.length && a[i] === b[i]) i++; return i; };
const cmp = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const ordenado = it => [...it].sort(cmp);
const claveSet = arr => [...new Set(arr)].sort(cmp).join('\u0001');   // frozenset() como clave de un mapa
const esSub = (a, b) => { for (const x of a) if (!b.has(x)) return false; return true; };   // a <= b
const inter = (a, b) => { const r = new Set(); for (const x of a) if (b.has(x)) r.add(x); return r; };
const hayInter = (a, b) => { for (const x of a) if (b.has(x)) return true; return false; };
/** Valor de un literal (como lo imprime Python con str()). */
class Num { constructor(txt, flotante) { this.txt = txt; this.flotante = flotante; }
  toString() { const v = Number(this.txt); return this.flotante ? (Number.isInteger(v) ? v.toFixed(1) : String(v)) : String(BigInt(this.txt)); } }

// ----------------------------------------------------------------------------- XML mínimo (un elemento por vez)

/** Arma el árbol de un pedazo de XML: {tag, a: {atributos}, k: [hijos], t: texto}. */
function parseXml(s) {
  const raiz = { tag: '#', a: {}, k: [], t: '' };
  const pila = [raiz];
  const re = /<!\[CDATA\[([\s\S]*?)\]\]>|<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<!DOCTYPE[^>]*>|<\/([^\s>]+)\s*>|<([^\s/>]+)((?:\s+[^\s=/>]+\s*=\s*(?:"[^"]*"|'[^']*'))*)\s*(\/?)>|([^<]+)/g;
  let m;
  while ((m = re.exec(s))) {
    const top = pila[pila.length - 1];
    if (m[1] != null) { if (!top.k.length) top.t += m[1]; continue; }
    if (m[2] != null) { if (pila.length > 1) pila.pop(); continue; }
    if (m[3] != null) {
      const el = { tag: m[3], a: {}, k: [], t: '' };
      const ra = /([^\s=/>]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g; let x;
      while ((x = ra.exec(m[4]))) el.a[x[1]] = unescapeXml(x[2] != null ? x[2] : x[3]);
      top.k.push(el);
      if (!m[5]) pila.push(el);
      continue;
    }
    if (m[6] != null && !top.k.length) top.t += unescapeXml(m[6]);   // ElementTree: .text = lo de antes del primer hijo
  }
  return raiz.k[0] || null;
}
const hijos = (el, tag) => el ? el.k.filter(x => x.tag === tag) : [];
const hijo = (el, tag) => el ? el.k.find(x => x.tag === tag) || null : null;
const textoDe = (el, tag) => { const x = hijo(el, tag); return x ? x.t : null; };   // findtext
function* recorrer(el, tag) { if (!el) return; if (el.tag === tag) yield el; for (const k of el.k) yield* recorrer(k, tag); }   // iter
function props(el) {
  const p = hijo(el, 'Properties'), r = {};
  if (p) for (const x of hijos(p, 'Property')) r[textoDe(x, 'Name')] = textoDe(x, 'Value');
  return r;
}

// ----------------------------------------------------------------------------- lectura de la KB (stream)

function nivel(l) {
  return { nombre: l.a.Name, desc: l.a.Description,
           atts: hijos(l, 'Attribute').map(a => ({ n: (a.t || '').trim(), key: a.a.key === 'True', nullable: a.a.isNullable === 'True', red: a.a.isRedundant === 'True' })),
           descAtt: textoDe(l, 'DescriptionAttribute'),
           niveles: hijos(l, 'Level').map(nivel) };
}

function wwDeInstancia(data) {
  data = (data || '').trim();
  if (!data.includes('<transaction')) return null;
  const x = parseXml(data.replace(/^<\?xml[^>]*\?>/, ''));
  if (!x) return null;
  const tr = hijo(x, 'transaction');
  const nombre = tr ? (tr.a.transaction || '').slice(37) : '';
  const lv = hijo(x, 'level');
  if (!nombre || !lv) return null;
  const att = e => (e.a.attribute || '').slice(37);
  const grilla = nodo => {
    if (!nodo) return [];
    const g = recorrer(nodo, 'grid').next().value;   // .//grid (también el propio nodo, que nunca es grid)
    if (!g) return [];
    const r = [];
    for (const ga of hijos(g, 'gridAttribute')) {
      const a = att(ga);
      if (!a || ga.a.visible === 'False') continue;
      const t = (ga.a.description || '').trim();
      r.push([a, t.startsWith('=') ? '' : t]);
    }
    return r;
  };
  const sel = hijo(lv, 'selection');
  let orden = [];
  const ords = sel ? recorrer(sel, 'orders').next().value : null;
  const o1 = ords ? hijo(ords, 'order') : null;
  if (o1) orden = hijos(o1, 'attribute').filter(a => att(a)).map(a => [att(a), a.a.ascending === 'False']);
  return [nombre, { cols: grilla(sel), orden, prompt: grilla(hijo(lv, 'prompt')) }];
}

function kbVacia() {
  return { attrs: {}, doms: {}, subs: {}, tablas: {}, indices: {}, trns: {}, objetivos: {}, ww: {}, ww_cond: {}, ww_inst: {},
           checksums: {}, info: {}, version: '', _version: 8 };
}

/** Procesa un elemento de 2do nivel del export (un <Attribute> de <Attributes> o un <Object> de <Objects>). */
function procesar(kb, xml) {
  const el = parseXml(xml);
  if (!el) return;
  if (el.tag === 'Attribute') { kb.attrs[el.a.name] = Object.assign(props(el), { _desc: el.a.description }); return; }
  if (el.tag !== 'Object') return;
  const t = el.a.type, n = el.a.name;
  if (t === T_DOM) kb.doms[n] = Object.assign(props(el), { _desc: el.a.description });
  else if (t === T_SUB) {
    for (const s of recorrer(el, 'Subtype')) {
      const nom = textoDe(s, 'Name');
      if (!kb.subs[nom]) kb.subs[nom] = { super: (textoDe(s, 'Supertype') || '').trim(), grupo: n, grupos: [] };
      kb.subs[nom].grupos.push(n);
    }
  } else if (t === T_WP && n && n.slice(0, 2).toUpperCase() === 'WW') {
    const parts = hijos(el, 'Part');
    const wf = parts.find(p => p.a.type === P_WEBFORM);
    const src = wf ? (textoDe(wf, 'Source') || '') : '';
    const m = src.match(/<gxGrid .*?<\/gxGrid>/s);
    if (m) kb.ww[n.slice(2).toLowerCase()] = m[0];
    const cond = parts.find(p => p.a.type === P_CONDITIONS);
    if (cond && (textoDe(cond, 'Source') || '').trim()) kb.ww_cond[n.slice(2).toLowerCase()] = textoDe(cond, 'Source');
  } else if (t === T_INST) {
    const p = hijo(el, 'Part'), d = p ? hijo(p, 'Data') : null;
    const r = wwDeInstancia(d ? d.t : '');
    if (r) kb.ww_inst[r[0].toLowerCase()] = r[1];
  } else if (t === T_TAB) {
    kb.tablas[n] = hijo(el, 'Part') ? [...recorrer(el, 'Item')].map(i => i.t.trim()) : [];
    kb.indices[n] = ordenado(new Set([...recorrer(el, 'Member')].map(m => (m.t || '').trim())));
  } else if (t === T_TRN) {
    kb.checksums[n] = el.a.checksum || '';
    const parts = {};
    for (const p of hijos(el, 'Part')) parts[p.a.type] = p;
    const st = parts[P_STRUCT];
    if (st) kb.trns[n] = { desc: el.a.description, niveles: hijos(st, 'Level').map(nivel) };
    const src = k => parts[k] ? (textoDe(parts[k], 'Source') || '') : '';
    kb.objetivos[n] = { webform: src(P_WEBFORM), reglas: src(P_RULES), eventos: src(P_EVENTS) };
    kb.info[n] = { modulo: el.a.moduleGuid || '', nombreCompleto: el.a.fullyQualifiedName || n, fecha: el.a.lastUpdate || '' };
  }
}

function normalizarKb(kb) {
  for (const a of Object.values(kb.attrs || {}))
    if (a.DomainKey && !a.idBasedOn && !a.ATTCUSTOMTYPE) a.idBasedOn = 'Domain:' + a.DomainKey;
  return kb;
}

/**
 * Lee la KB de un stream de texto (ReadableStream de strings o un iterable asíncrono de strings).
 * avance(bytesLeidos) se llama de vez en cuando.
 */
async function leerKbTexto(trozos, avance) {
  const kb = kbVacia();
  let buf = '', seccion = null, leidos = 0, cabecera = '';
  const proximo = /<(\/?)(Objects|Attributes)\s*>|<(Object|Attribute)[\s>]/g;
  const consumir = (final) => {
    let pos = 0;
    for (;;) {
      proximo.lastIndex = pos;
      const m = proximo.exec(buf);
      if (!m) { pos = Math.max(pos, buf.length - 20); break; }   // deja la cola por si un tag quedó partido
      if (m[2]) { seccion = m[1] ? null : m[2]; pos = proximo.lastIndex; continue; }
      const tag = m[3];
      if ((tag === 'Object' && seccion !== 'Objects') || (tag === 'Attribute' && seccion !== 'Attributes')) { pos = m.index + 1; continue; }
      const cierre = '</' + tag + '>';
      // un elemento vacío (<Object ... />)
      const finTag = buf.indexOf('>', m.index);
      if (finTag < 0) { pos = m.index; break; }
      if (buf[finTag - 1] === '/') { procesar(kb, buf.slice(m.index, finTag + 1)); pos = finTag + 1; continue; }
      // el cierre que le corresponde: algunos objetos (temas, design systems) tienen otros <Object> adentro
      const reAnid = new RegExp('<' + tag + '[\\s>]|' + cierre, 'g');
      reAnid.lastIndex = finTag + 1;
      let prof = 1, fin = -1, x;
      while ((x = reAnid.exec(buf))) {
        if (x[0] === cierre) { if (--prof === 0) { fin = x.index; break; } }
        else if (buf[buf.indexOf('>', x.index) - 1] !== '/') prof++;
      }
      if (fin < 0) { pos = m.index; break; }   // falta leer más
      procesar(kb, buf.slice(m.index, fin + cierre.length));
      pos = fin + cierre.length;
    }
    if (!final) buf = buf.slice(pos);
  };
  for await (const t of trozos) {
    if (cabecera.length < 4000) cabecera += t.slice(0, 4000 - cabecera.length);
    buf += t; leidos += t.length;
    if (buf.length > 1 << 20) consumir(false);
    if (avance) avance(leidos);
  }
  consumir(true);
  const mv = cabecera.match(/<KMW>\s*<MajorVersion>(\d+)<\/MajorVersion>\s*<MinorVersion>(\d+)<\/MinorVersion>\s*<Build>(\d+)<\/Build>/);
  if (mv) kb.version = mv[1] + '.' + mv[2] + '.' + mv[3];
  const nk = cabecera.match(/<KnowledgeBase name="([^"]*)"/);
  kb.nombre = nk ? unescapeXml(nk[1]) : '';
  return normalizarKb(kb);
}

/** Bytes (ReadableStream<Uint8Array>) -> texto, por partes. */
async function* textoDeBytes(stream) {
  const dec = new TextDecoder('utf-8');
  const r = stream.getReader();
  for (;;) {
    const { done, value } = await r.read();
    if (done) break;
    yield dec.decode(value, { stream: true });
  }
  const resto = dec.decode();
  if (resto) yield resto;
}

/** Los XML de adentro de un XPZ (zip). archivo: Blob/File. Devuelve [{nombre, abrir: () => ReadableStream<Uint8Array>}]. */
async function xmlsDeZip(archivo) {
  const tam = archivo.size;
  const cola = new DataView(await archivo.slice(Math.max(0, tam - 66000), tam).arrayBuffer());
  let eocd = -1;
  for (let i = cola.byteLength - 22; i >= 0; i--) if (cola.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('el archivo no es un XPZ (zip) válido');
  let total = cola.getUint16(eocd + 10, true), tamCd = cola.getUint32(eocd + 12, true), iniCd = cola.getUint32(eocd + 16, true);
  if (iniCd === 0xffffffff) throw new Error('XPZ de más de 4 GB (zip64): exportá un XML en su lugar');
  const cd = new DataView(await archivo.slice(iniCd, iniCd + tamCd).arrayBuffer());
  const r = [];
  for (let p = 0, i = 0; i < total; i++) {
    if (cd.getUint32(p, true) !== 0x02014b50) break;
    const metodo = cd.getUint16(p + 10, true), comp = cd.getUint32(p + 20, true);
    const ln = cd.getUint16(p + 28, true), le = cd.getUint16(p + 30, true), lc = cd.getUint16(p + 32, true);
    const local = cd.getUint32(p + 42, true);
    const nombre = new TextDecoder().decode(new Uint8Array(cd.buffer, cd.byteOffset + p + 46, ln));
    p += 46 + ln + le + lc;
    if (!/\.xml$/i.test(nombre)) continue;
    r.push({ nombre, tamano: comp, abrir: async () => {
      const lh = new DataView(await archivo.slice(local, local + 30).arrayBuffer());
      const ini = local + 30 + lh.getUint16(26, true) + lh.getUint16(28, true);
      const datos = archivo.slice(ini, ini + comp).stream();
      if (metodo === 0) return datos;
      if (metodo === 8) return datos.pipeThrough(new DecompressionStream('deflate-raw'));
      throw new Error('el XPZ usa una compresión que no se conoce (' + metodo + ')');
    } });
  }
  return r;
}

/** Lee un archivo .xml o .xpz (Blob/File con .name). avance(fraccion 0..1). */
async function leerArchivo(archivo, avance) {
  if (/\.xpz$/i.test(archivo.name || '') || (await archivo.slice(0, 2).text()) === 'PK') {
    const xs = await xmlsDeZip(archivo);
    if (!xs.length) throw new Error('el XPZ no tiene ningún XML adentro');
    xs.sort((a, b) => b.tamano - a.tamano);   // el export es el XML grande
    const total = archivo.size * 6;           // estimado (el XML comprimido ocupa ~1/6)
    return leerKbTexto(textoDeBytes(await xs[0].abrir()), n => avance && avance(Math.min(0.99, n / total)));
  }
  return leerKbTexto(textoDeBytes(archivo.stream()), n => avance && avance(Math.min(0.99, n / archivo.size)));
}

// ----------------------------------------------------------------------------- modelo

const DOMINIOS_GENEXUS = {
  'Address, GeneXus': { ATTCUSTOMTYPE: 'bas:VarChar', Length: '1024' },
  'Email, GeneXus': { ATTCUSTOMTYPE: 'bas:VarChar', Length: '100' },
  'Url, GeneXus': { ATTCUSTOMTYPE: 'bas:VarChar', Length: '1000' },
  'Phone, GeneXus': { ATTCUSTOMTYPE: 'bas:Character', Length: '20' },
  'Html, GeneXus': { ATTCUSTOMTYPE: 'bas:LongVarChar', Length: '2000' },
  'Geolocation, GeneXus': { ATTCUSTOMTYPE: 'bas:Character', Length: '50' },
  'Time, GeneXus': { ATTCUSTOMTYPE: 'bas:DateTime' },
};
const conCT = (base, fuente) => { const r = Object.assign({}, base); if ('ControlType' in fuente) r.ControlType = fuente.ControlType; return r; };

class Modelo {
  constructor(kb) {
    this.kb = kb;
    this.tablaPorClaves = new Map();
    for (const [k, v] of Object.entries(kb.tablas)) if (v.length) this.tablaPorClaves.set(claveSet(v), k);
    this.clavesDe = new Map(Object.entries(kb.tablas).map(([k, v]) => [k, new Set(v)]));
    this.todasLasClaves = new Set(); for (const s of this.clavesDe.values()) for (const x of s) this.todasLasClaves.add(x);
    this.noClaves = new Map(); this.noClavesLit = new Map(); this._niveles = [];
    for (const trn of Object.values(kb.trns)) for (const lv of trn.niveles) this._recorrer(lv, []);
    let base = this.noClaves, baseLit = this.noClavesLit;
    for (let pasada = 0; pasada < 2; pasada++) {
      this.guardados = new Map(); this.guardadosLit = new Map();
      for (const [tabla, atts, todos] of this._niveles)
        for (const n of atts)
          if (!this._inferidoEn(n, tabla, todos, base, baseLit)) {
            if (!this.guardados.has(tabla)) this.guardados.set(tabla, new Set());
            this.guardados.get(tabla).add(n); this.guardados.get(tabla).add(this.norm(n));
            if (!this.guardadosLit.has(tabla)) this.guardadosLit.set(tabla, new Set());
            this.guardadosLit.get(tabla).add(n);
          }
      base = this.guardados; baseLit = this.guardadosLit;
    }
  }
  _inferidoEn(n, tabla, todos, base, baseLit) {
    const gs = this.grupos(n);
    if (gs.size) {
      const miembros = new Set(todos.filter(c => hayInter(this.grupos(c), gs)).map(c => this.norm(c)));
      const na = this.norm(n);
      for (const k2 of this.clavesDe.values()) if (k2.size && esSub(k2, miembros) && !k2.has(na)) return true;
      return false;
    }
    const lit = new Set(todos);
    for (const [t2, k2] of this.clavesDe) if (t2 !== tabla && k2.size && esSub(k2, lit) && !k2.has(n) && (baseLit.get(t2) || new Set()).has(n)) return true;
    return false;
  }
  _recorrer(lv, clavesPadre) {
    const claves = clavesPadre.concat(lv.atts.filter(a => a.key).map(a => a.n));
    const tabla = this.tablaPorClaves.get(claveSet(claves));
    if (tabla) {
      if (!this.noClaves.has(tabla)) this.noClaves.set(tabla, new Set());
      if (!this.noClavesLit.has(tabla)) this.noClavesLit.set(tabla, new Set());
      const s = this.noClaves.get(tabla), sl = this.noClavesLit.get(tabla);
      const atts = lv.atts.filter(a => !a.key && !this.formula(a.n)).map(a => a.n);
      for (const n of atts) { s.add(n); s.add(this.norm(n)); sl.add(n); }
      this._niveles.push([tabla, atts, claves.concat(lv.atts.map(a => a.n))]);
    }
    for (const sub of lv.niveles) this._recorrer(sub, claves);
  }
  columnaEn(n, tabla) {
    const visto = new Set(); let a = n;
    while (!visto.has(a)) {
      if ((this.guardadosLit.get(tabla) || new Set()).has(a)) return a;
      visto.add(a);
      if (!(a in this.kb.subs)) break;
      a = this.kb.subs[a].super;
    }
    return this.norm(n);
  }
  norm(a) { const visto = new Set(); while (a in this.kb.subs && !visto.has(a)) { visto.add(a); a = this.kb.subs[a].super; } return a; }
  grupos(a) { const s = this.kb.subs[a]; return s ? new Set(s.grupos && s.grupos.length ? s.grupos : [s.grupo]) : new Set(); }
  formula(a) { return (this.kb.attrs[a] || {}).Formula || null; }
  /** [dominio, 'Tipo(l.d)', valores|null, fuente] */
  tipo(a) {
    const base = this.norm(a);
    const p = this.kb.attrs[a] || {}, pb = this.kb.attrs[base] || {};
    let fuente = (p.ATTCUSTOMTYPE || p.idBasedOn) ? p : pb;
    let dom = null;
    if ((fuente.idBasedOn || '').startsWith('Domain:')) {
      dom = splitN(fuente.idBasedOn, ':', 1)[1];
      if (!(dom in this.kb.doms) && dom in DOMINIOS_GENEXUS) fuente = conCT(DOMINIOS_GENEXUS[dom], fuente);
      else fuente = conCT(this.kb.doms[dom] || {}, fuente);
    }
    const t = (fuente.ATTCUSTOMTYPE || 'bas:Numeric').split(':').pop();
    let lon = parseInt(fuente.Length || 0, 10) || 0;
    const dec = parseInt(fuente.Decimals || 0, 10) || 0;
    const mapa = { Numeric: 'Numeric', Character: 'Character', VarChar: 'VarChar', LongVarChar: 'VarChar', Date: 'Date', DateTime: 'DateTime', Boolean: 'Boolean', GUID: 'GUID' };
    const tt = Object.prototype.hasOwnProperty.call(mapa, t) ? mapa[t] : null;
    if (tt === 'GUID') return [dom, 'GUID', null, fuente];
    if (tt == null) return [dom, null, null, fuente];
    let txt;
    if (tt === 'Numeric') { lon = lon || 4; txt = dec ? `Numeric(${lon}.${dec})` : `Numeric(${lon})`; }
    else if (tt === 'Character' || tt === 'VarChar') { if (t === 'LongVarChar' && lon < 1) lon = 2097152; lon = lon || 20; txt = `${tt}(${lon})`; }
    else txt = tt;
    let en = null;
    if (fuente.IDEnumDefinedValues) {
      en = [];
      for (const parte of fuente.IDEnumDefinedValues.split(';')) {
        const v = splitN(parte, ',', 2).map(x => x.trim());
        if (v.length === 3) en.push({ valor: strip(v[0].trim(), '"'), nombre: v[1], texto: v[2] });
      }
    }
    if (en == null && tt !== 'Boolean') {
      for (const fp of [p, pb]) {
        if (fp.ControlType === 'Combo Box' && fp.ControlValues) {
          en = [];
          for (const parte of fp.ControlValues.split(',')) {
            if (!parte.includes(':')) continue;
            const i = parte.lastIndexOf(':'), texto = parte.slice(0, i), valor = parte.slice(i + 1);
            en.push({ valor: strip(valor.trim(), '"'), nombre: texto.trim(), texto: texto.trim() });
          }
          if (en.length) dom = dom == null ? base[0].toUpperCase() + base.slice(1) : dom;
          else en = null;
          break;
        }
      }
    }
    return [dom, txt, en, fuente];
  }
  valorInicial(a) {
    const p = this.kb.attrs[a] || {};
    const v = (p.ATT_INITIAL_VALUE || '').trim();
    if (!v) return null;
    if (v.toLowerCase() === 'true' || v.toLowerCase() === 'false') return v.toLowerCase() === 'true';
    if (/^-?\d+$/.test(v)) return new Num(v, false);
    if (/^-?\d+\.\d+$/.test(v)) return new Num(v, true);
    const m = v.match(/^["'](.*)["']$/);
    return m ? m[1] : null;
  }
  autonumerico(a) { const f = this.tipo(a)[3]; return f.AUTONUMBER === 'True' || (this.kb.attrs[a] || {}).AUTONUMBER === 'True'; }
  etiqueta(a) { const p = this.kb.attrs[a] || this.kb.attrs[this.norm(a)] || {}; return p.ContextualTitle || p.Description || p._desc || a; }

  analizarNivel(atts, clavesPadre, ctxExtra) {
    const claves = clavesPadre.concat(atts.filter(a => a.key).map(a => a.n));
    const tabla = this.tablaPorClaves.get(claveSet(claves)) || null;
    const ctx = atts.map(a => a.n).concat(clavesPadre, ctxExtra);
    const propios = [], inferidos = new Map(), formulas = [], descartados = [];
    const refs = new Map();   // clave "t2|fk" -> {t2, fk, trae: [[l, r]]}
    const ctxSimple = new Set(ctx.filter(c => !this.grupos(c).size));
    for (const a of atts) {
      const n = a.n;
      if (a.key) continue;
      if (this.formula(n)) { formulas.push(n); continue; }
      if (this.formula(this.norm(n))) { descartados.push(n); continue; }
      const gs = this.grupos(n);
      let elegido = null;
      if (gs.size) {
        const miembros = new Map();
        for (const c of ctx) if (hayInter(this.grupos(c), gs)) miembros.set(this.norm(c), c);
        const mset = new Set(miembros.keys()), na = this.norm(n);
        let cands = [...this.clavesDe].filter(([t2, k2]) => k2.size && esSub(k2, mset) && !k2.has(na) && (this.guardados.get(t2) || new Set()).has(na)).map(x => x[0]);
        if (!cands.length)
          cands = [...this.clavesDe].filter(([t2, k2]) => k2.size && esSub(k2, mset) && !k2.has(na) && (this.noClaves.get(t2) || new Set()).has(na)).map(x => x[0]);
        if (cands.length) {
          let t2 = cands[0];
          for (const c of cands) if (this.clavesDe.get(c).size > this.clavesDe.get(t2).size) t2 = c;
          elegido = [t2, ordenado(this.clavesDe.get(t2)).map(k => [miembros.get(k), k]), this.columnaEn(n, t2)];
        }
      }
      if (elegido == null) {
        const cands = [...this.clavesDe].filter(([t2, k2]) => k2.size && t2 !== tabla && esSub(k2, ctxSimple) && !k2.has(n) && (this.guardados.get(t2) || new Set()).has(n)).map(x => x[0]);
        if (cands.length) {
          const clave = t => [commonPrefix(t, n), -this.clavesDe.get(t).size];
          let t2 = cands[0];
          for (const c of cands) { const a1 = clave(c), b1 = clave(t2); if (a1[0] > b1[0] || (a1[0] === b1[0] && a1[1] > b1[1])) t2 = c; }
          elegido = [t2, ordenado(this.clavesDe.get(t2)).map(k => [k, k]), n];
        }
      }
      if (elegido == null && gs.size && !this.todasLasClaves.has(this.norm(n))
          && Object.entries(this.kb.subs).some(([x, e]) => hayInter(gs, new Set(e.grupos && e.grupos.length ? e.grupos : [e.grupo])) && this.todasLasClaves.has(this.norm(x)))) {
        descartados.push(n); continue;
      }
      if (elegido == null) { propios.push(n); continue; }
      const [t2, fk, col] = elegido;
      const k = t2 + '|' + JSON.stringify(fk);
      if (!refs.has(k)) refs.set(k, { t2, fk, trae: [] });
      refs.get(k).trae.push([n, col]);
      inferidos.set(n, [t2, fk]);
    }
    return { tabla, claves, propios, inferidos, formulas, refs, descartados };
  }
  atributoDescripcion(tabla) {
    for (const trn of Object.values(this.kb.trns)) for (const lv of trn.niveles) { const r = this._buscarDesc(lv, [], tabla); if (r) return r; }
    return null;
  }
  _buscarDesc(lv, cp, tabla) {
    const claves = cp.concat(lv.atts.filter(a => a.key).map(a => a.n));
    if (this.tablaPorClaves.get(claveSet(claves)) === tabla && sinNone(lv.descAtt)) return sinNone(lv.descAtt);
    for (const s of lv.niveles) { const r = this._buscarDesc(s, claves, tabla); if (r) return r; }
    return null;
  }
}

// ----------------------------------------------------------------------------- web form y reglas

function gx17AHtml(s) {
  const m = s.match(/<Form id="1"[^>]*>(.*?)<\/Form>/s);
  s = m ? m[1] : s;
  s = s.split(/<actionGroups>/)[0];
  s = s.replace(/PATTERN_ELEMENT_CUSTOM_PROPERTIES="[^"]*"/g, '');
  const atributo = (a, tag) => { const r = tag.match(new RegExp('\\b' + a + '="([^"]*)"')); return r ? r[1] : null; };
  s = s.replace(/<simplegrid\b([^>]*)>(.*?)<\/simplegrid>/gs, (m0, g1, g2) => {
    const nombre = atributo('controlName', g1) || 'GridLevel';
    let cols = '';
    for (const it of g2.matchAll(/<item\b[^>]*\/?>/g)) {
      const a = atributo('attribute', it[0]);
      if (!a) continue;
      const tit = atributo('titleExp', it[0]) || atributo('title', it[0]) || a;
      const vis = atributo('visible', it[0]) === 'False' ? 'False' : 'True';
      cols += `<gxColumn ColAttId="${a}" ColTitleExpression="${tit}" ColVisible="${vis}" />`;
    }
    return `<gxGrid ControlName="${nombre}">${cols}</gxGrid>`;
  });
  // E4b: las solapas de GeneXus 17 (<tab><item itemControlName=.. caption=..>...</item></tab>) como las de GeneXus 15
  let nSolapa = 0;
  s = s.replace(/<tab\b[^>]*>/g, '<gxgxui.TabPanel>').split('</tab>').join('</gxgxui.TabPanel>');
  s = s.replace(/<item\b[^>]*\bitemControlName="[^"]*"[^>]*>/g, m0 => {
    nSolapa++;
    const cap = atributo('caption', m0) || atributo('itemControlName', m0) || 'Solapa ' + nSolapa;
    return `<container containerId="TitletabPanel${nSolapa}">${cap}</container><container containerId="tabPanel${nSolapa}">`;
  }).split('</item>').join('</container>');
  s = s.replace(/<textblock\b[^>]*\/>/g, m0 => { const cap = atributo('caption', m0); return cap ? `<gxTextBlock CaptionExpression="${cap}" />` : ''; });
  s = s.replace(/<data\b[^>]*\/>/g, tag => {
    const att = atributo('attribute', tag);
    if (!att) return '';
    const cap = atributo('labelCaption', tag);
    const txt = cap && atributo('labelPosition', tag) !== 'None' ? `<gxTextBlock CaptionExpression="${cap}" />` : '';
    return txt + `<gxAttribute AttID="${att}" />`;
  });
  s = s.replace(/<row\b/g, '<tr').split('</row>').join('</tr>');
  s = s.replace(/<cell\b/g, '<td').split('</cell>').join('</td>');
  return s;
}

function leerSolapas(webform) {
  let s = webform;
  if (s.slice(0, 400).includes('type="layout"')) s = gx17AHtml(s);
  const internos = [], pila = [];
  let fin = -1;
  for (const m of s.matchAll(/<gxgxui\.TabPanel[ >]|<\/gxgxui\.TabPanel>/g)) {
    if (m[0].startsWith('</')) {
      if (!pila.length) continue;
      const ini = pila.pop();
      if (pila.length) internos.push([ini, m.index + m[0].length]);
      else { fin = m.index; break; }
    } else pila.push(m.index);
  }
  if (fin > 0) s = s.slice(0, fin);
  const dentro = p => internos.some(([a, b]) => a <= p && p < b);
  let pos = [...s.matchAll(/<container containerId="(tabPanel\d+)"/g)].filter(m => !dentro(m.index)).map(m => [m[1], m.index]);
  let titulos = {};
  for (const m of s.matchAll(/<container containerId="Title(tabPanel\d+)">([^<]*)/g))
    if (!dentro(m.index) && !(m[1] in titulos)) titulos[m[1]] = m[2];
  if (!pos.length) {
    const f = s.indexOf('<table id="TableActions"');
    const seg = f > 0 ? s.slice(0, f) : s;
    pos = [['tabPanel0', 0]]; titulos = { tabPanel0: '' }; s = seg;
  }
  const solapas = [];
  pos.forEach(([tid, p], i) => {
    const seg = s.slice(p, i + 1 < pos.length ? pos[i + 1][1] : s.length);
    const campos = []; let ultimo = null;
    for (const m of seg.matchAll(/<gxTextBlock CaptionExpression="([^"]*)"[^>]*\/>|<gxAttribute AttID="([^"]+)"/g)) {
      if (m[1] != null) ultimo = unescapeHtml(m[1]);
      else { const a = unescapeHtml(m[2]); if (!a.startsWith('&')) campos.push([a, ultimo || a]); ultimo = null; }
    }
    const renglones = renglonesDe(seg);
    const grillas = [];
    for (const g of seg.matchAll(/<gxGrid [^>]*ControlName="(?:GridLevel_)?([A-Za-z0-9_]+)"(.*?)<\/gxGrid>/gs)) {
      const cols = [...g[2].matchAll(/<gxColumn [^>]*ColAttId="([^"]+)"[^>]*ColTitleExpression="([^"]*)"[^>]*ColVisible="(\w+)"/g)]
        .map(c => [unescapeHtml(c[1]), unescapeHtml(c[2]), c[3] !== 'False']);
      grillas.push([g[1], cols.filter(c => !c[0].startsWith('&'))]);
    }
    solapas.push({ id: parseInt(tid.slice(8), 10), titulo: unescapeHtml(tid in titulos ? titulos[tid] : tid), campos, renglones, grillas });
  });
  return solapas;
}

function renglonesDe(seg) {
  const raiz = { tipo: 'td', hijos: [] };
  const pila = [raiz];
  for (const m of seg.matchAll(/<(\/?)(table|tr|td)\b[^>]*?(\/?)>|<gxAttribute AttID="([^"]+)"|<gxGrid\b.*?<\/gxGrid>/gs)) {
    if (m[4] != null) { const a = unescapeHtml(m[4]); if (!a.startsWith('&')) pila[pila.length - 1].hijos.push(a); continue; }
    if (m[2] == null) continue;
    const cierre = m[1] === '/', tag = m[2], auto = m[3] === '/';
    if (cierre) {
      while (pila.length > 1 && pila[pila.length - 1].tipo !== tag) pila.pop();
      if (pila.length > 1) pila.pop();
    } else if (!auto) {
      const nodo = { tipo: tag, hijos: [] };
      pila[pila.length - 1].hijos.push(nodo); pila.push(nodo);
    }
  }
  const deCelda = td => {
    const filas = []; let directos = [];
    for (const h of td.hijos) {
      if (typeof h === 'string') directos.push(h);
      else if (h.tipo === 'table') { if (directos.length) { filas.push(directos); directos = []; } filas.push(...deTabla(h)); }
      else if (h.tipo === 'tr' || h.tipo === 'td') filas.push(...(h.tipo === 'tr' ? deTabla({ hijos: [h] }) : deCelda(h)));
    }
    if (directos.length) filas.push(directos);
    return filas;
  };
  const deTabla = t => {
    const filas = [];
    for (const tr of t.hijos) {
      if (typeof tr === 'string' || tr.tipo !== 'tr') { if (typeof tr !== 'string' && tr.tipo === 'table') filas.push(...deTabla(tr)); continue; }
      const celdas = tr.hijos.filter(td => typeof td !== 'string' && td.tipo === 'td').map(deCelda).filter(c => c.length);
      if (!celdas.length) continue;
      const max = Math.max(...celdas.map(c => c.length));
      for (let i = 0; i < max; i++) { const fila = []; for (const c of celdas) if (i < c.length) fila.push(...c[i]); if (fila.length) filas.push(fila); }
    }
    return filas;
  };
  return deCelda(raiz);
}

function limpiarReglas(txt) {
  txt = txt.replace(/\/\*.*?\*\//gs, '');
  txt = txt.replace(/\/\/[^\n]*/g, '');
  const win = [...txt.matchAll(/\[win\]\s*\{(.*?)\}/gs)].map(m => m[1]).join(' ');
  txt = txt.replace(/\[win\]\s*\{.*?\}/gs, '');
  txt += ';' + [...win.matchAll(/default\([^;]*\)/gi)].map(m => 'WINDEFAULT' + m[0]).join(';');
  txt = txt.replace(/\[web\]\s*\{/g, '');
  txt = txt.split('}').join('');
  return txt.split(';').map(r => r.trim()).filter(Boolean);
}

function literal(v) {
  v = v.trim();
  if (/^-?\d+(\.\d+)?$/.test(v)) return new Num(v, v.includes('.'));
  if (v.toLowerCase() === 'true' || v.toLowerCase() === 'false') return v.toLowerCase() === 'true';
  let m = v.match(/^['"](.*)['"]$/s);
  if (m) return m[1];
  m = v.match(/^([A-Za-z]\w*)\.([A-Za-z]\w*)$/);
  if (m) return { enum: [m[1], m[2]] };
  return null;
}

function analizarReglas(reglas) {
  const r = { default_win: {}, visibleSi: {}, noaccept: new Set(), default: {}, error_vacio: {}, serial: {}, auditoria: {}, prompt: new Set(), pendientes: [] };
  for (const x of reglas) {
    const xs = pySplit(x).join(' ');
    let m;
    if ((m = xs.match(/^noaccept\((\w+)\)$/i))) { r.noaccept.add(m[1]); continue; }
    if ((m = xs.match(/^(\w+)\.Visible\s*=\s*\(?\s*&ModuloMutual\s*=\s*true\s*\)?$/))) { r.visibleSi[m[1]] = 'ModuloMutual'; continue; }
    if ((m = xs.match(/^prompt\(\s*\w+\s*,\s*(\w+).*\)$/i) || xs.match(/^prompt\((\w+)\)$/i))) { r.prompt.add(m[1]); continue; }
    if (/^noprompt\((\w+)\)$/i.test(xs)) continue;
    if ((m = xs.match(/^WINDEFAULTdefault\(\s*(\w+)\s*,\s*(.+)\)$/i))) { const v = literal(m[2]); if (v != null) r.default_win[m[1]] = v; continue; }
    if ((m = xs.match(/^default\(\s*(\w+)\s*,\s*&WWPContext\.(\w+)\s*\)$/i))) { r.default[m[1]] = '&' + m[2]; continue; }
    if ((m = xs.match(/^default\(\s*(\w+)\s*,\s*(.+)\)$/i))) { const v = literal(m[2]); if (v != null) r.default[m[1]] = v; else r.pendientes.push(xs); continue; }
    if ((m = xs.match(/^error\(\s*['"](.+?)['"]\s*\)\s*if\s+(\w+)\.IsEmpty\(\)$/i))) { r.error_vacio[m[2]] = m[1]; continue; }
    if ((m = xs.match(/^error\(\s*format\(\s*['"]WWP_RequiredAttribute['"]\s*,\s*['"](.+?)['"]\s*\)\s*\)\s*if\s+(\w+)\.IsEmpty\(\)$/i))) { r.error_vacio[m[2]] = 'Ingrese ' + m[1]; continue; }
    if ((m = xs.match(/^serial\(\s*(\w+)\s*,\s*(\w+)(\s*,\s*\d+)?\s*\)$/i))) { r.serial[m[1]] = m[2]; continue; }
    if ((m = xs.match(/^(\w+)\s*=\s*(Now\(\)|&WWPContext\.SecUserName)\s+if\s+&Mode\s*=\s*TrnMode\.(Insert|Update)\s+on\s+AfterValidate$/))) {
      const k = (m[3] === 'Insert' ? 'alta' : 'modificacion') + (m[2].startsWith('Now') ? 'Fecha' : 'Usuario');
      r.auditoria[k] = m[1]; continue;
    }
    if (/^(\w+)\s*=\s*&\1\s+if\s+not\s+&\1\.IsEmpty\(\)$/.test(xs) || /^noaccept\((\w+)\)\s+if\s+not\s+&\1\.IsEmpty\(\)$/i.test(xs)) continue;
    if (/^parm\(/i.test(xs) || /&Insert_\w+/.test(xs) || /_Cell\.Class/.test(xs) || /&ArticulosPK\b/.test(xs)) continue;
    r.pendientes.push(xs);
  }
  return r;
}

// ----------------------------------------------------------------------------- YAML

const noSoportadoTipo = (M, n) => M.tipo(n)[1] == null;

function singular(p) {
  const low = p.toLowerCase();
  if (low.endsWith('iones')) return p.slice(0, -5) + 'ión';
  if (low.endsWith('íses') || low.endsWith('eses')) return p.slice(0, -2);
  if (low.endsWith('ces') && low.length > 4) return p.slice(0, -3) + 'z';
  if (/[rlndjy]es$/.test(low) && !low.endsWith('lles')) return p.slice(0, -2);
  if (low.endsWith('s') && low.length > 3 && !(low.endsWith('is') || low.endsWith('us'))) return p.slice(0, -1);
  return p;
}
function nombresDe(titulo) {
  const t = (titulo || '').trim();
  const out = []; let corte = false;
  for (const w of t.split(' ')) {
    if (['de', 'del', 'por', 'para', 'con', 'en', 'y'].includes(w.toLowerCase())) corte = true;
    out.push(corte || isUpper(w) ? w : singular(w));
  }
  const sing = out.join(' ');
  const s0 = out[0].toLowerCase();
  const fem = ['a', 'ión', 'dad', 'tud', 'ez'].some(x => s0.endsWith(x)) && !['ma', 'pa'].some(x => s0.endsWith(x));
  const minus = x => !isUpper(x.slice(0, 2)) ? x.slice(0, 1).toLowerCase() + x.slice(1) : x;
  return [minus(sing), minus(t), fem ? 'f' : null];
}
function sinNone(v) { v = (v || '').trim(); return v === '(None)' || v === 'None' ? '' : v; }
function q(s) {
  s = String(s);
  if (s === '' || /[:#\[\]{},&*!|>'"%@`?]/.test(s) || s.trim() !== s || ['true', 'false', 'null', 'yes', 'no', 'on', 'off'].includes(s.toLowerCase())
      || /^[+-]?0\d+$/.test(s))
    return '"' + s.split('\\').join('\\\\').split('"').join('\\"') + '"';
  return s;
}

function filtrosContexto(cond) {
  const r = {};
  let sc = (cond || '').replace(/\/\*.*?\*\//gs, ' ');
  sc = sc.replace(/\/\/[^\n]*/g, ' ');
  for (const linea of sc.split(';')) {
    let m = linea.match(/^\s*(\w+)\s*=\s*&WWPContext\.(\w+)\s*$/);
    if (m) { r[m[1]] = '&' + m[2]; continue; }
    m = linea.match(/^\s*(\w+)\s*(<>|>=|<=|>|<|=)\s*(-?\d+(?:\.\d+)?|'[^']*'|"[^"]*")\s*$/);
    if (m && !(m[1] in r)) { const v = strip(m[3], '\'"'); r[m[1]] = m[2] === '=' ? v : m[2] + v; }
  }
  return r;
}

function leerWw(grid) {
  const cols = [];
  for (const c of grid.matchAll(/<gxColumn ([^>]*)\/>/g)) {
    const at = {}; for (const x of c[1].matchAll(/(\w+)="([^"]*)"/g)) at[x[1]] = x[2];
    const a = unescapeHtml(at.ColAttId || '');
    if (!a || a.startsWith('&') || (at.ColVisible || 'True') === 'False') continue;
    cols.push([a, unescapeHtml(at.ColTitleExpression || '').trim()]);
  }
  const orden = [];
  const m = grid.match(/ControlOrder="([^"]*)"/);
  if (m) {
    let linea = unescapeHtml(m[1]).split('\n')[0];
    linea = linea.replace(/\bwhen\b.*$/i, '');
    for (const tok of linea.matchAll(/\(?\s*[A-Za-z_][A-Za-z0-9_]*\s*\)?/g)) orden.push([strip(tok[0], '() '), tok[0].trim().startsWith('(')]);
  }
  return [cols, orden];
}

function cerrarCadenas(info, mostrar) {
  mostrar = new Set(mostrar); const extra = new Set();
  for (;;) {
    const nuevos = new Set();
    for (const { fk, trae } of info.refs.values())
      if (trae.some(([l]) => mostrar.has(l)))
        for (const [l] of fk) if (info.inferidos.has(l) && !mostrar.has(l)) nuevos.add(l);
    if (!nuevos.size) return [mostrar, extra];
    for (const x of nuevos) { mostrar.add(x); extra.add(x); }
  }
}

// ----------------------------------------------------------------------------- fórmulas

function enumerados(kb) {
  const r = {};
  for (const [dn, d] of Object.entries(kb.doms)) {
    const vals = d.IDEnumDefinedValues;
    if (!vals) continue;
    const texto = ['Character', 'VarChar', 'LongVarChar'].includes((d.ATTCUSTOMTYPE || '').split(':').pop());
    for (const parte of vals.split(/(?<!\\);/)) {
      const v = splitN(parte, ',', 2).map(x => x.trim());
      if (v.length >= 2 && v[1]) { const val = strip(v[0], '"'); r[`${dn}.${v[1]}`.toLowerCase()] = texto ? `"${val}"` : val; }
    }
  }
  Object.assign(r, { 'trnmode.insert': '"INS"', 'trnmode.update': '"UPD"', 'trnmode.delete': '"DLT"', 'trnmode.display': '"DSP"' });
  return r;
}
function reemplazarEnums(src, enums) {
  const partes = src.split(/("[^"\n]*"|'[^'\n]*')/);
  for (let i = 0; i < partes.length; i += 2)
    partes[i] = partes[i].replace(/(?<![&\w.])[A-Za-z_]\w*\.[A-Za-z_]\w*(?![\w(.])/g, m => Object.prototype.hasOwnProperty.call(enums, m.toLowerCase()) ? enums[m.toLowerCase()] : m);
  return partes.join('');
}
function sinComentarios(txt) {
  const out = []; let i = 0, qq = null;
  while (i < txt.length) {
    const c = txt[i];
    if (qq) { out.push(c); if (c === qq || c === '\n') qq = null; }
    else if (c === '"' || c === "'") { qq = c; out.push(c); }
    else if (txt.startsWith('/*', i)) { const j = txt.indexOf('*/', i + 2); i = j < 0 ? txt.length : j + 2; out.push(' '); continue; }
    else if (txt.startsWith('//', i)) { while (i < txt.length && txt[i] !== '\n') i++; continue; }
    else out.push(c);
    i++;
  }
  return out.join('');
}
function limpiarFormula(M, txt, enums) {
  let f = pySplit(sinComentarios(txt || '')).join(' ').trim();
  f = f.replace(/;\s*$/, '');
  f = reemplazarEnums(f, enums);
  return f.replace(/\bfind\s*\(\s*([A-Za-z_]\w*)\s*,/gi, (m0, col) => {
    let tablas = [...M.guardadosLit].filter(([, cols]) => cols.has(col)).map(x => x[0]);
    if (!tablas.length) tablas = [...M.noClavesLit].filter(([, cols]) => cols.has(col)).map(x => x[0]);
    return tablas.length ? `find(${tablas[0]}.${col},` : m0;
  });
}
function atributosFormula(kb, f) {
  const r = new Set();
  for (const m of f.replace(/"[^"]*"|'[^']*'/g, '').matchAll(/(?<![&\w.])([A-Za-z_]\w*)(?![\w.(])/g)) if (m[1] in kb.attrs) r.add(m[1]);
  return r;
}
function dependencias(kb, M, semillas, enums) {
  const necesarios = new Set(), pendientes = [...semillas];
  while (pendientes.length) {
    const a = pendientes.pop();
    const fo = M.formula(a);
    if (!fo) continue;
    for (const d of atributosFormula(kb, limpiarFormula(M, fo, enums))) if (!necesarios.has(d)) { necesarios.add(d); pendientes.push(d); }
  }
  return necesarios;
}

let _modelo = null;
function modelo(kb) { if (!_modelo || _modelo.kb !== kb) _modelo = new Modelo(kb); return _modelo; }

// ----------------------------------------------------------------------------- generar (gx2yaml.generar)

function valorPy(v) { return typeof v === 'boolean' ? String(v) : String(v); }

function generar(kb, trnNombre, solapasIds) {
  solapasIds = solapasIds || new Set();
  const textos = {}, previosDet = {};
  const M = modelo(kb);
  const trn = kb.trns[trnNombre];
  const marcadas = new Set();
  const red_ = lv => { for (const a of lv.atts) if (a.red) marcadas.add(a.n); lv.niveles.forEach(red_); };
  trn.niveles.forEach(red_);
  const esRedundante = (n, tabla) => marcadas.has(n) || (kb.indices[tabla || ''] || []).includes(n);
  const cab = trn.niveles[0];
  const obj = kb.objetivos[trnNombre];
  const solapas = leerSolapas(obj.webform).filter(s => !solapasIds.size || solapasIds.has(s.id));
  if ((obj.webform || '').slice(0, 400).includes('type="layout"')) {
    const ordenCab = trn.niveles[0].atts.map(a => a.n);
    const descr = {};
    for (const [tn, t] of Object.entries(kb.trns)) {
      const n0 = t.niveles && t.niveles.length ? t.niveles[0] : null;
      if (!n0 || tn === trnNombre) continue;
      const ks = n0.atts.filter(a => a.key).map(a => a.n);
      if (ks.length !== 1 || !ordenCab.includes(ks[0])) continue;
      const otros = new Set(n0.atts.filter(a => !a.key).map(a => a.n));
      let d = n0.descAtt;
      if (!d || !ordenCab.includes(d)) d = ordenCab.slice(ordenCab.indexOf(ks[0]) + 1).find(x => otros.has(x)) || null;
      if (d && !(ks[0] in descr)) descr[ks[0]] = d;
    }
    const propiosCab = new Set(ordenCab);
    for (const s_ of solapas) {
      const ya = new Set(s_.campos.map(([a]) => a));
      const nuevos = [];
      for (const [a, t] of s_.campos) {
        nuevos.push([a, t]);
        const d = descr[a];
        if (d && !ya.has(d) && propiosCab.has(d) && !trn.niveles[0].atts.some(x => x.key && x.n === a)) {
          nuevos.push([d, M.etiqueta(d)]); ya.add(d);
          for (const r of s_.renglones) if (r.includes(a) && !r.includes(d)) r.splice(r.indexOf(a) + 1, 0, d);
        }
      }
      s_.campos = nuevos;
    }
  }
  const reglas = analizarReglas(limpiarReglas(obj.reglas));

  let camposEnSolapas = new Set(solapas.flatMap(s => s.campos.map(([a]) => a)));
  if (!(obj.webform || '').trim()) {
    const todos = lv => { const r = new Set(lv.atts.map(a => a.n)); for (const sub of lv.niveles) for (const x of todos(sub)) r.add(x); return r; };
    camposEnSolapas = todos(cab);
  }
  const etiquetaForm = {};
  for (const s of solapas) for (const [a, t] of s.campos) etiquetaForm[a] = t;
  const nivelesCab = trn.niveles[0].niveles.map(lv => [lv.nombre, new Set(lv.atts.map(a => a.n))]);
  const usados = new Set();
  for (const s_ of solapas) {
    const nuevas = [];
    for (const [, cols] of s_.grillas) {
      const attsG = new Set(cols.map(c => c[0]));
      let mejor = [0, null];
      for (const [k, v] of nivelesCab) {
        if (usados.has(k)) continue;
        const c = inter(attsG, v).size;
        if (c > mejor[0] || (c === mejor[0] && c > 0 && k > mejor[1])) mejor = [c, k];   // max() de tuplas (cantidad, nombre)
      }
      if (mejor[0] > 0) { usados.add(mejor[1]); nuevas.push([mejor[1], cols]); }
    }
    s_.grillas = nuevas;
  }
  const grillasElegidas = new Map();
  for (const s of solapas) for (const [g, cols] of s.grillas) grillasElegidas.set(g, cols);

  const dominiosUsados = new Map();
  const emitidos = new Set();
  const clavesCab_ = trn.niveles[0].atts.filter(a => a.key).map(a => a.n);
  const fksActuales = new Set();
  const lineas = [], out = l => lineas.push(l);
  const noSoportados = [], archivosCab = [], omitidos = [];
  const nulables = new Set();
  const marcarNulables = lv => { for (const a of lv.atts) if (a.nullable) nulables.add(a.n); lv.niveles.forEach(marcarNulables); };
  marcarNulables(cab);

  const enums = enumerados(kb);
  const campoYaml = (n, ind, o = {}) => {
    const { clave = false, inferido = false, formula = null, etiqueta = null, oculto = false, redundante = false } = o;
    const [dom, txt, en, fuente] = M.tipo(n);
    if (txt == null) {
      if ((fuente.ATTCUSTOMTYPE || '').endsWith('Blob') && ind === 2 && !inferido && !formula) { archivosCab.push([n, etiqueta || M.etiqueta(n), fuente]); return; }
      noSoportados.push(n); return;
    }
    const nombreDom = dom || null;
    if (nombreDom && !dominiosUsados.has(nombreDom)) dominiosUsados.set(nombreDom, [txt, en]);
    if (ind === 2) emitidos.add(n);
    const partes = [`nombre: ${n}`, `dominio: ${q(nombreDom || txt)}`, `etiqueta: ${q(etiqueta || M.etiqueta(n))}`];
    if (clave) partes.push('clave: true');
    if (clave && ind === 2 && clavesCab_.length === 1 && M.autonumerico(n)) partes.push('autonumerico: true');
    if (inferido) partes.push('inferido: true');
    if (formula) partes.push('formula: ' + q(limpiarFormula(M, formula, enums)));
    if (formula && redundante) partes.push('guardar: true');
    if (reglas.noaccept.has(n) && !clave) partes.push('soloLectura: true');
    let vDef = n in reglas.default ? reglas.default[n] : null;
    if (vDef == null && clave && !fksActuales.has(n)) vDef = n in reglas.default_win ? reglas.default_win[n] : null;
    if (vDef == null && !clave && !inferido && !formula) vDef = M.valorInicial(n);
    if (vDef != null) {
      let v = vDef;
      if (v && typeof v === 'object' && v.enum) { const ev = (en || []).find(e => e.nombre.toLowerCase() === v.enum[1].toLowerCase()); v = ev ? ev.valor : null; }
      if (v != null) partes.push('default: ' + (typeof v === 'string' ? q(v) : valorPy(v)));
    }
    if (txt === 'GUID' && !clave && !inferido && vDef == null) partes.push('default: "&NewGuid"');
    if (n in reglas.error_vacio && !clave) { partes.push('obligatorio: true'); partes.push('mensaje: ' + q(reglas.error_vacio[n])); }
    if (n in reglas.visibleSi) partes.push('visibleSi: ' + reglas.visibleSi[n]);
    if (nulables.has(n) && !clave && !inferido) partes.push('nulo: true');
    if (oculto) partes.push('oculto: true');
    out(' '.repeat(ind) + '- {' + partes.join(', ') + '}');
  };
  const referenciasYaml = (info, ind) => {
    if (!info.refs.size) return;
    out(' '.repeat(ind) + 'referencias:');
    for (const { t2, fk, trae } of info.refs.values()) {
      const desc = M.atributoDescripcion(t2);
      out(' '.repeat(ind) + `  - tabla: ${t2}`);
      out(' '.repeat(ind) + '    campos: {' + fk.map(([l, r]) => `${l}: ${r}`).join(', ') + '}');
      out(' '.repeat(ind) + '    trae: {' + trae.map(([l, r]) => `${l}: ${r}`).join(', ') + '}');
      if (desc) out(' '.repeat(ind) + `    descripcion: ${desc}`);
    }
  };
  const filtrarRefs = (info, mostrar) => {
    const nr = new Map();
    for (const [k, v] of info.refs) { const trae = v.trae.filter(([l]) => mostrar.has(l)); if (trae.length) nr.set(k, { t2: v.t2, fk: v.fk, trae }); }
    info.refs = nr;
  };

  const semillas = new Set();
  const idxCab = kb.indices[M.tablaPorClaves.get(claveSet(cab.atts.filter(a => a.key).map(a => a.n))) || ''] || [];
  for (const a_ of cab.atts) if (M.formula(a_.n) && (camposEnSolapas.has(a_.n) || idxCab.includes(a_.n))) semillas.add(a_.n);
  for (const lv_ of cab.niveles) {
    const cols_ = grillasElegidas.get(lv_.nombre);
    if (cols_ == null) continue;
    const vis_ = new Set(cols_.filter(c => c[2]).map(c => c[0]));
    for (const a_ of lv_.atts) if (M.formula(a_.n) && vis_.has(a_.n)) semillas.add(a_.n);
  }
  const necesarios = dependencias(kb, M, semillas, enums);

  // ---------------- cabecera
  const info = M.analizarNivel(cab.atts, [], []);
  out(`# TRN ${trnNombre} - GENERADA desde la KB GeneXus con herramientas/gx2yaml.py`);
  out(`# Solapas incluidas: ${solapas.map(s => s.titulo).join(', ')}`);
  out('# Los campos propios que no están en esas solapas se incluyen ocultos: se preservan al modificar');
  out('# y se graban vacíos en el alta.');
  out(`transaccion: ${trnNombre}`);
  const titulo = textos.titulo || trn.desc || trnNombre;
  out(`titulo: ${q(titulo)}`);
  const [sing_, plur_, gen_] = nombresDe(titulo);
  if (!('singular' in textos)) textos.singular = sing_;
  out(`singular: ${q(textos.singular)}`);
  out(`plural: ${q(textos.plural || plur_)}`);
  if (textos.genero || gen_) out(`genero: ${textos.genero || gen_}`);
  out(`tabla: ${info.tabla == null ? 'None' : info.tabla}`);
  const numeradores = new Set(Object.values(reglas.serial));
  {
    let desc = sinNone(cab.descAtt);
    if (numeradores.has(desc) || (desc && noSoportadoTipo(M, desc))) desc = null;
    desc = desc || info.propios.find(c => /(Descrip|Nombre|RazonSocial)$/.test(c)) || '';
    // E4b: un texto largo (ej. la descripción completa de un incidente) no va en el encabezado: se busca uno corto
    const largo = a => { const r = M.tipo(a)[1].match(/\((\d+)/); return r ? +r[1] : 0; };
    if (desc && largo(desc) > 200)
      desc = info.propios.find(c => c !== desc && /(Descort|Descrip|Nombre|RazonSocial|Titulo)$/.test(c) && largo(c) > 0 && largo(c) <= 200) || '';
    const propiosEnc = info.propios.includes('ArticulosId') ? ['ArticulosId'] : cab.atts.filter(a => a.key).map(a => a.n);
    const sing = textos.singular || trnNombre.toLowerCase();
    const partes = [sing.slice(0, 1).toUpperCase() + sing.slice(1), '{' + propiosEnc[0] + '}'];
    if (desc && desc !== propiosEnc[0]) partes.push('-', '{' + desc + '}');
    out('encabezado: "' + partes.join(' ') + '"');
  }
  if (Object.keys(reglas.auditoria).length) out('auditoria: {' + Object.entries(reglas.auditoria).map(([k, v]) => `${k}: ${v}`).join(', ') + '}');
  out('campos:');
  const orden = cab.atts.map(a => a.n).filter(n => !numeradores.has(n));
  let wwGx = [], ordenGx = [], promptGx = [];
  const attsCab = new Set(cab.atts.map(a => a.n));
  const inst = kb.ww_inst[trnNombre.toLowerCase()];
  if (kb.ww[trnNombre.toLowerCase()]) [wwGx, ordenGx] = leerWw(kb.ww[trnNombre.toLowerCase()]);
  else if (inst) { wwGx = inst.cols.slice(); ordenGx = inst.orden.slice(); promptGx = inst.prompt.slice(); }
  const okWw = c => attsCab.has(c) && !info.formulas.includes(c) && !info.descartados.includes(c) && !numeradores.has(c) && !noSoportadoTipo(M, c);
  wwGx = wwGx.filter(([c]) => okWw(c));
  promptGx = promptGx.filter(([c]) => okWw(c));
  const wwInferidos = new Set(wwGx.concat(promptGx).map(([c]) => c).filter(c => info.inferidos.has(c)));
  const necInfCab = new Set([...necesarios].filter(c => info.inferidos.has(c)));
  const [mostrarCab, extraCab] = cerrarCadenas(info, new Set([...camposEnSolapas, ...wwInferidos, ...necInfCab]));
  for (const x of [...wwInferidos, ...necInfCab]) if (!camposEnSolapas.has(x)) extraCab.add(x);
  const auditoriaVals = new Set(Object.values(reglas.auditoria));
  for (const n of orden) {
    const a = cab.atts.find(x => x.n === n);
    if (noSoportadoTipo(M, n)) {
      const fu = M.tipo(n)[3];
      if ((fu.ATTCUSTOMTYPE || '').endsWith('Blob') && !info.inferidos.has(n) && !info.formulas.includes(n)) archivosCab.push([n, etiquetaForm[n] || M.etiqueta(n), fu]);
      continue;
    }
    if (info.descartados.includes(n)) { omitidos.push(n); continue; }
    if (a.key) campoYaml(n, 2, { clave: true, etiqueta: etiquetaForm[n] });
    else if (info.formulas.includes(n)) {
      const red = esRedundante(n, info.tabla);
      if (camposEnSolapas.has(n) || red || necesarios.has(n))
        campoYaml(n, 2, { formula: M.formula(n), etiqueta: etiquetaForm[n], redundante: red, oculto: !camposEnSolapas.has(n) });
    } else if (info.inferidos.has(n)) {
      if (mostrarCab.has(n)) campoYaml(n, 2, { inferido: true, etiqueta: etiquetaForm[n], oculto: extraCab.has(n) });
    } else campoYaml(n, 2, { etiqueta: etiquetaForm[n], oculto: !camposEnSolapas.has(n) && !auditoriaVals.has(n) });
  }
  filtrarRefs(info, mostrarCab);
  referenciasYaml(info, 0);

  // ---------------- detalles
  const detalles = [];
  const clavesCab = cab.atts.filter(a => a.key).map(a => a.n);
  for (const lv of cab.niveles) {
    if (!grillasElegidas.has(lv.nombre)) continue;
    const cols = grillasElegidas.get(lv.nombre);
    const visibles = new Set(cols.filter(c => c[2]).map(c => c[0]));
    const tit = {}; for (const [c, t] of cols) tit[c] = t;
    const inf = M.analizarNivel(lv.atts, clavesCab, []);
    detalles.push([lv, inf, visibles, tit]);
  }
  for (const [, inf_] of detalles) for (const { fk } of inf_.refs.values()) for (const [l] of fk) fksActuales.add(l);
  if (detalles.length) {
    out('detalles:');
    for (const [lv, inf, visibles, tit] of detalles) {
      const prev = previosDet[lv.nombre] || {};
      out(`  - nombre: ${lv.nombre}`);
      out(`    titulo: ${prev.titulo || q(lv.desc || lv.nombre)}`);
      out(`    tabla: ${inf.tabla == null ? 'None' : inf.tabla}`);
      const clavesDet = lv.atts.filter(a => a.key).map(a => a.n);
      let ser = clavesDet.filter(c => c in reglas.serial);
      if (ser.length && clavesDet.length > 1) {
        ser = [];
        out('    # en GeneXus ' + clavesDet[0] + ' se numeraba solo (serial); acá el detalle tiene clave compuesta');
      }
      if (ser.length) out(`    numerador: ${reglas.serial[ser[0]]}`);
      out('    campos:');
      const necInf = new Set([...necesarios].filter(c => inf.inferidos.has(c)));
      const [mostrarDet, extraDet] = cerrarCadenas(inf, new Set([...visibles, ...necInf]));
      for (const x of necInf) if (!visibles.has(x)) extraDet.add(x);
      for (const a of lv.atts) {
        const n = a.n;
        if (inf.descartados.includes(n)) { omitidos.push(n); continue; }
        if (a.key) campoYaml(n, 6, { clave: true, etiqueta: tit[n], oculto: !visibles.has(n) && !(n in reglas.serial && !ser.length) });
        else if (inf.formulas.includes(n)) {
          const red = esRedundante(n, inf.tabla);
          if (red || visibles.has(n) || necesarios.has(n)) campoYaml(n, 6, { formula: M.formula(n), etiqueta: tit[n], redundante: red, oculto: !visibles.has(n) });
          continue;
        } else if (inf.inferidos.has(n)) { if (mostrarDet.has(n)) campoYaml(n, 6, { inferido: true, etiqueta: tit[n], oculto: extraDet.has(n) }); }
        else campoYaml(n, 6, { etiqueta: tit[n], oculto: !visibles.has(n) });
      }
      filtrarRefs(inf, mostrarDet);
      referenciasYaml(inf, 4);
    }
  }

  const estirable = n => { const t = M.tipo(n); const m = (t[1] || '').match(/^(VarChar|Character)\((\d+)\)/); return !!m && parseInt(m[2], 10) >= 20 && !t[2]; };

  // ---------------- pantalla
  out('pantalla: |');
  for (const s_ of solapas) {
    const vistos = new Set(); let filas = [];
    for (const r of s_.renglones) { const f = r.filter(a => emitidos.has(a) && !vistos.has(a)); f.forEach(a => vistos.add(a)); if (f.length) filas.push(f); }
    const resto = s_.campos.map(([a]) => a).filter(a => emitidos.has(a) && !vistos.has(a));
    if (resto.length) filas.push(resto);
    const aud = reglas.auditoria, audSet = new Set(Object.values(aud));
    const pos = filas.findIndex(f => f.some(a => audSet.has(a)));
    let filasAud = [];
    if (pos >= 0) {
      filasAud = [[aud.altaFecha, aud.altaUsuario].filter(a => a && emitidos.has(a)), [aud.modificacionFecha, aud.modificacionUsuario].filter(a => a && emitidos.has(a))].filter(x => x.length);
      filas = filas.map(f => f.filter(a => !audSet.has(a))).filter(r => r.length);
    }
    const gs = s_.grillas.map(([g]) => g).filter(g => detalles.some(d => d[0].nombre === g));
    if (!filas.length && !gs.length && !filasAud.length) continue;
    const conSolapa = !!s_.titulo;
    const ind = conSolapa ? '  ' : '';
    if (conSolapa) out(`  <solapa ${s_.titulo}>`);
    if (filas.length) {
      const ncol = Math.max(...filas.map(f => f.length));
      const celdas = filas.map(f => { const c = f.slice(); if (c.length < ncol && estirable(c[c.length - 1])) c[c.length - 1] = `${c[c.length - 1]}:${ncol - c.length + 1}`; return c; });
      const anchos = []; for (let i = 0; i < ncol; i++) anchos.push(Math.max(...celdas.filter(c => i < c.length).map(c => c[i].length)));
      out(ind + '  <tabla>');
      for (const c of celdas) out((ind + '    ' + c.map((x, i) => ljust(x, anchos[i])).join('  ')).replace(/\s+$/, ''));
      out(ind + '  <endtable>');
    }
    if (filasAud.length) {
      const w = Math.max(...filasAud.map(f => f[0].length)) + 4;
      out(ind + '  <tabla anchos=300px,1fr>');
      for (const f of filasAud) out(ind + '    ' + (f.length > 1 ? ljust(f[0], w) + f[1] : f[0]));
      out(ind + '  <endtable>');
    }
    for (const g of gs) out(ind + `  <detalle ${g}>`);
    if (conSolapa) out('  <endsolapa>');
  }

  if (archivosCab.length) {
    const propios = new Set(cab.atts.map(a => a.n));
    out('archivos:');
    for (const [n, et, pr] of archivosCab) {
      const partes = [`nombre: ${n}`, `etiqueta: ${q(et)}`];
      for (const [clave, prop, suf] of [['nombreArchivo', 'AttFileNameAtt', 'FileName'], ['tipoArchivo', 'AttFileTypeAtt', 'FileType']]) {
        const c = pr[prop] || (n + suf);
        if (propios.has(c) && emitidos.has(c)) partes.push(`${clave}: ${c}`);
      }
      out('  - {' + partes.join(', ') + '}');
    }
  }
  out('workwith:');
  const propiosSinNum = info.propios.filter(c => !numeradores.has(c) && emitidos.has(c));
  const art = ['ArticulosId', 'ArticulosDescrip', 'ArticulosDescort', 'ArticulosEstado'].filter(c => info.propios.includes(c));
  const propiosWw = art.length ? art : clavesCab.concat(propiosSinNum).slice(0, 4);
  wwGx = wwGx.filter(([c]) => emitidos.has(c));
  const conTitulo = ([c, t]) => t && t !== (etiquetaForm[c] || M.etiqueta(c)) && t !== c ? q(`${c}: ${t}`) : c;
  if (wwGx.length) out('  columnas: [' + wwGx.map(conTitulo).join(', ') + ']');
  else out('  columnas: [' + propiosWw.join(', ') + ']');
  promptGx = promptGx.filter(([c]) => emitidos.has(c));
  if (promptGx.length) out('  prompt: [' + promptGx.map(conTitulo).join(', ') + ']');
  const guardables = new Set([...emitidos].filter(c => clavesCab.includes(c) || info.propios.includes(c)));
  const filtro = Object.entries(filtrosContexto(kb.ww_cond[trnNombre.toLowerCase()])).filter(([c]) => guardables.has(c));
  if (filtro.length) out('  filtro: {' + filtro.map(([c, v]) => `${c}: ${q(v)}`).join(', ') + '}');
  const redundantes = new Set(info.formulas.filter(c => esRedundante(c, info.tabla)));
  ordenGx = ordenGx.filter(([c]) => guardables.has(c) || (redundantes.has(c) && emitidos.has(c)));
  if (ordenGx.length) out('orden: [' + ordenGx.map(([c, d]) => c + (d ? ' DESC' : '')).join(', ') + ']');
  else out('orden: [' + (propiosWw.find(c => !clavesCab.includes(c)) || propiosWw[0]) + ']');

  out('dominios:');
  for (const d of ordenado(dominiosUsados.keys())) {
    const [txt, en] = dominiosUsados.get(d);
    if (en) {
      out(`  ${d}:`); out(`    tipo: ${q(txt)}`); out('    valores:');
      for (const e of en) out(`      - {valor: ${q(e.valor)}, texto: ${q(e.texto)}}`);
    } else out(`  ${d}: ${q(txt)}`);
  }

  out('');
  out('# ---------------------------------------------------------------- PENDIENTE');
  out('# Reglas de GeneXus que el motor todavía no interpreta (van a la clase de reglas Java o a una próxima versión):');
  for (const p of reglas.pendientes) out('#   ' + [...p].slice(0, 230).join(''));
  if (noSoportados.length) out('# Atributos con tipos no soportados todavía (Blob/Image/GUID...): ' + noSoportados.join(', '));
  if (omitidos.length) out('# Omitidos (en GeneXus son fórmulas o llegan por otra tabla que este nivel no referencia): ' + omitidos.join(', '));
  return { yaml: lineas.join('\n') + '\n', pendientes: reglas.pendientes, noSoportados, tablasRef: [...new Set([...info.refs.values()].map(r => r.t2).concat(detalles.flatMap(([, inf]) => [...inf.refs.values()].map(r => r.t2))))] };
}

// ----------------------------------------------------------------------------- para el importador

/** Las TRN importables de la KB (las de datos: con clave), sin las del patrón (WWPBaseObjects). */
function listarTrns(kb) {
  const M = modelo(kb);
  const r = [];
  for (const [n, t] of Object.entries(kb.trns)) {
    const inf = kb.info[n] || {};
    const base = /^WWPBaseObjects\./.test(inf.nombreCompleto || '');
    const claves = t.niveles.length ? t.niveles[0].atts.filter(a => a.key).map(a => a.n) : [];
    let problema = '';
    if (!claves.length) problema = 'no tiene clave (en GeneXus no es una transacción de datos)';
    else { const raras = claves.filter(c => M.tipo(c)[1] == null); if (raras.length) problema = 'la clave ' + raras.join(', ') + ' es de un tipo que el motor todavía no maneja (Blob/Image)'; }
    const tabla = claves.length ? M.tablaPorClaves.get(claveSet(claves)) || '' : '';
    const contar = lv => 1 + lv.niveles.reduce((s, x) => s + contar(x), 0);
    r.push({ nombre: n, desc: t.desc || n, base, problema, tabla, niveles: t.niveles.length ? contar(t.niveles[0]) : 0,
             checksum: kb.checksums[n] || '', fecha: inf.fecha || '' });
  }
  return r.sort((a, b) => cmp(a.nombre.toLowerCase(), b.nombre.toLowerCase()));
}
/** tabla -> TRN que la define (por la clave de su primer nivel o de sus niveles). */
function trnDeTabla(kb) {
  const M = modelo(kb), r = {};
  const rec = (trn, lv, cp) => { const claves = cp.concat(lv.atts.filter(a => a.key).map(a => a.n)); const t = M.tablaPorClaves.get(claveSet(claves)); if (t && !(t in r)) r[t] = trn; lv.niveles.forEach(s => rec(trn, s, claves)); };
  for (const [n, t] of Object.entries(kb.trns)) if (t.niveles.length) rec(n, t.niveles[0], []);
  return r;
}
/** Texto para pasarle a la IA: los eventos y las reglas que el importador no tradujo. */
function textoParaIa(kb, trn, res) {
  const o = kb.objetivos[trn] || {};
  const l = [];
  l.push(`# ${trn} — lo que el importador de OPTIMIST Engine no tradujo solo`);
  l.push('');
  l.push(`KB: ${kb.nombre || ''} (export ${kb.version || '?'}) · TRN: ${trn} · ${(kb.trns[trn] || {}).desc || ''}`);
  l.push('');
  l.push('Cómo usarlo: pasale este archivo y trn/' + trn + '.yaml a la IA que uses, junto con GUIA_CONFIGURACION_TRN.md de OPTIMIST Engine,');
  l.push('y pedile que traduzca las reglas y eventos de abajo a las secciones "reglas:" / "inicio:" del YAML (o a procedimientos .proc),');
  l.push('respetando el formato de la guía. Las reglas que agrega el patrón WorkWithPlus (seguridad, contexto, navegación) el motor');
  l.push('ya las resuelve solo: no hace falta traducirlas.');
  l.push('');
  l.push('## Reglas no traducidas');
  l.push(res.pendientes.length ? res.pendientes.map(p => '- ' + p).join('\n') : '(ninguna)');
  if (res.noSoportados.length) { l.push(''); l.push('## Atributos de tipos que el motor todavía no maneja'); l.push(res.noSoportados.join(', ')); }
  l.push('');
  l.push('## Reglas (completas, como están en GeneXus)');
  l.push('```');
  l.push((o.reglas || '').trim());
  l.push('```');
  l.push('');
  l.push('## Eventos (completos, como están en GeneXus)');
  l.push('```');
  l.push((o.eventos || '').trim());
  l.push('```');
  return l.join('\n') + '\n';
}

return { leerArchivo, leerKbTexto, generar, listarTrns, trnDeTabla, textoParaIa, modelo, parseXml, unescapeHtml };
})();
if (typeof module !== 'undefined' && module.exports) module.exports = GXI;
