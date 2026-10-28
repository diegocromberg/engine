import subprocess,re,sys,glob,os,tempfile
# Chequeo de compilación sin Maven (sin Vaadin ni Spring): solo informa los errores "reales" (los de nuestras clases).
# Uso: python3 chequear_java.py <carpeta java> [<otra carpeta java> ...]
#   ej. desde OPTIMIST ERP: python3 ../engine/herramientas/chequear_java.py ../engine/src/main/java src/main/java
files=[]
for src in sys.argv[1:]: files+=glob.glob(src+'/**/*.java',recursive=True)
out=subprocess.run(['javac','-proc:none','-XDshould-stop.ifError=FLOW','-Xmaxerrs','100000','-d',os.path.join(tempfile.gettempdir(),'optimist-chk')]+files,capture_output=True,text=True).stderr
bloques=re.split(r'\n(?=\S.*\.java:\d+: error:)',out)
ext=('com.vaadin','org.springframework','org.slf4j','org.yaml','jakarta.','org.apache','com.mysql','org.atmosphere')
reales=[]
fuentes=''.join(open(f,encoding='utf-8').read() for f in files)
propias0={f.split('/')[-1][:-5] for f in files}
derivadas=set()
for f in files:
    m=re.search(r'class (\w+)[^{]*?extends (\w+)',open(f,encoding='utf-8').read())
    if m and m.group(2) not in propias0: derivadas.add(m.group(1))
for b in bloques:
    if ': error:' not in b: continue
    if 'does not exist' in b.split('\n')[0] or 'method does not override' in b: continue
    if 'cannot access' in b: continue
    if 'cannot find symbol' in b or 'incompatible types' in b or 'cannot be applied' in b or 'no suitable' in b:
        loc=re.search(r'location: (.*)',b)
        l=loc.group(1) if loc else ''
        # real only if the symbol is looked up in one of our own classes
        propias={f.split('/')[-1][:-5] for f in files}
        m=re.match(r'(?:class|interface|variable \w+ of type) ([\w.]+)',l)
        tipo=m.group(1).split('.')[-1].split('<')[0] if m else ''
        sym=re.search(r'symbol:\s+(\w+)',b)
        if sym and sym.group(1)=='class': continue
        sm=re.search(r'symbol:\s+method (\w+)',b)
        if sm and tipo in derivadas and not re.search(r'(public|private|protected|static)[^;=\n]*\b'+sm.group(1)+r'\s*\(', fuentes): continue
        sv=re.search(r'symbol:\s+variable (\w+)',b)
        if sv and sv.group(1)[0].isupper(): continue
        if (('ar.com.datafix' in l) or tipo in propias) and not any(e in b for e in ext): reales.append(b); continue
        if 'cannot find symbol' in b: continue
        if any(x in b for x in ['Button','Dialog','Grid','Component','TextField','Notification']): continue
    reales.append(b)
print('\n'.join(reales[:40]) if reales else 'OK sin errores reales'); print(len(reales),'errores')
