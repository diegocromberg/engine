// Genera con el importador JS las TRN pedidas y las graba en una carpeta (para comparar con gx2yaml.py)
const fs = require('fs'), path = require('path');
const GXI = require(process.argv[2]);
const [,, , xml, salida, lista] = process.argv;
(async () => {
  async function* trozos() { for await (const t of fs.createReadStream(xml, { encoding: 'utf8', highWaterMark: 1 << 16 })) yield t; }
  const t0 = Date.now();
  const kb = await GXI.leerKbTexto(trozos());
  console.error('KB', kb.nombre, kb.version, Object.keys(kb.trns).length, 'TRN en', Date.now() - t0, 'ms');
  fs.mkdirSync(salida, { recursive: true });
  const nombres = lista === '*' ? GXI.listarTrns(kb).filter(t => !t.base && !t.problema).map(t => t.nombre) : lista.split(',');
  for (const n of nombres) {
    try { fs.writeFileSync(path.join(salida, n + '.yaml'), GXI.generar(kb, n).yaml); }
    catch (e) { console.error('ERROR', n, e.stack); }
  }
  console.error('generadas', nombres.length);
})();
