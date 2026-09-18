import re,sys
# Usage: gen_locale_data.py <LocaleDataTables.kt> <out android-locale-data.txt>. See README.md.
src=open(sys.argv[1]).read()  # LocaleDataTables.kt from the aaptcompiler sources jar, see README.md
def block(name):
    i=src.index('internal val '+name)
    j=src.find('internal ', i+10)
    return src[i:j if j>0 else len(src)]
codes=[''.join(re.findall(r"'(.)'\.code", l)) for l in block('SCRIPT_CODES').splitlines() if 'byteArrayOf(' in l]
assert all(len(c)==4 for c in codes), codes[:3]
out=[]
out.append("# Android locale data used by strings.AndroidLocaleData (build-logic). Generated, do not edit by hand.")
out.append("# Source: AOSP frameworks/base/libs/androidfw/LocaleDataTables.cpp, via the Kotlin transliteration")
out.append("# shipped in com.android.tools.build:aaptcompiler:9.1.1 (com.android.aaptcompiler.android.LocaleDataTablesKt).")
out.append("# Copyright (C) The Android Open Source Project. Licensed under the Apache License, Version 2.0.")
out.append("# S <packed lang+region, hex> <likely script>   (LIKELY_SCRIPTS)")
out.append("# P <script> <packed child, hex> <packed parent, hex>   (SCRIPT_PARENTS)")
out.append("# R <packed lang+region+script, hex>   (REPRESENTATIVE_LOCALES)")
n=0
for m in re.finditer(r"(-?0x[0-9a-fA-F]+)(?:\.toInt\(\))?\s+to\s+(\d+)", block('LIKELY_SCRIPTS')):
    v=int(m.group(1),16) & 0xffffffff
    out.append("S %08x %s"%(v, codes[int(m.group(2))])); n+=1
np=0
for script in ['ARAB','HANT','LATN']:
    b=block(script+'_PARENTS')
    for m in re.finditer(r"(-?0x[0-9a-fA-F]+)(?:\.toInt\(\))?\s+to\s+(-?0x[0-9a-fA-F]+)", b):
        out.append("P %s %08x %08x"%(script.capitalize(), int(m.group(1),16)&0xffffffff, int(m.group(2),16)&0xffffffff)); np+=1
nr=0
for m in re.finditer(r"(-?0x[0-9a-fA-F]+)L", block('REPRESENTATIVE_LOCALES')):
    out.append("R %016x"%(int(m.group(1),16) & 0xffffffffffffffff)); nr+=1
# script parents map keys check
assert 'mapOf("Arab" to ARAB_PARENTS, "Hant" to HANT_PARENTS, "Latn" to LATN_PARENTS)' in src
print(n,np,nr,file=sys.stderr)
open(sys.argv[2],'w').write('\n'.join(out)+'\n')
