"""Check Android translation completeness and formatting placeholders."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent.parent
sets={}
for name in ['values','values-cs','values-de','values-es','values-fr']:
    elements=ET.parse(root/'res'/name/'strings.xml').getroot().findall('string')
    keys=[e.attrib['name'] for e in elements]
    assert len(keys)==len(set(keys)), 'Duplicate resource in '+name
    sets[name]={e.attrib['name']:''.join(e.itertext()) for e in elements}
base=sets['values']
for locale,strings in sets.items():
    assert strings.keys()==base.keys(), 'Missing/extra resource in '+locale
    for key,value in strings.items():
        assert value.strip('"'), 'Empty translation: '+locale+'/'+key
        fmt=lambda s:re.findall(r'%(?:\d+\$)?[0-9.]*[a-zA-Z]',s)
        assert fmt(value)==fmt(base[key]), 'Format mismatch: '+locale+'/'+key
service=(root/'src/cz/codex/rtl8153diagnostic/EthernetVpnService.java').read_text()
activity=(root/'src/cz/codex/rtl8153diagnostic/MainActivity.java').read_text()
assert not re.search(r'status(?:Text\(\))?\.startsWith',service+activity), 'Connection logic must not depend on translated text'
print('PASS:',len(base),'resources in five languages; complete keys, matching placeholders and language-independent state checks')
