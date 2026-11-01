import struct, sys, subprocess
b=open('m.grib','rb').read()
pos=16; ref=e=d=bits=n=0; dp=-1; tmpl=-1
def s16(x):
    return -(x & 0x7fff) if x & 0x8000 else x
while pos+5<=len(b):
    if b[pos:pos+4]==b'7777': break
    ln=struct.unpack('>I',b[pos:pos+4])[0]; sec=b[pos+4]
    if sec==5:
        n=struct.unpack('>I',b[pos+5:pos+9])[0]; tmpl=struct.unpack('>H',b[pos+9:pos+11])[0]
        ref=struct.unpack('>f',b[pos+11:pos+15])[0]; e=s16(struct.unpack('>H',b[pos+15:pos+17])[0]); d=s16(struct.unpack('>H',b[pos+17:pos+19])[0]); bits=b[pos+19]
    if sec==7: dp=pos+5
    pos+=ln
print('n',n,'tmpl',tmpl,'ref',ref,'E',e,'D',d,'bits',bits)
vals=[ (ref+ (struct.unpack('>H',b[dp+2*i:dp+2*i+2])[0])*2**e)/10**d for i in range(n)]
out=subprocess.run(['grib_get_data','m.grib'],capture_output=True,text=True).stdout.split('\n')[1:]
ok=0;bad=0
for k,line in enumerate(out[:579121:7919]):
    p=line.split()
    if len(p)<3: continue
    lat,lon,v=map(float,p)
    i=round((lon-3.0)/0.025); j=round((lat-33.7)/0.02)
    mine=vals[j*761+i]
    if abs(mine-v)<1e-3*max(1,abs(v)): ok+=1
    else: bad+=1; print('diff',lat,lon,v,mine)
print('ok',ok,'bad',bad)
