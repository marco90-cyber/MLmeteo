import struct, sys, math
import numpy as np
f=open('sri.tif','rb').read()
le=f[:2]==b'II'; bo='<' if le else '>'
ifd=struct.unpack(bo+'I',f[4:8])[0]; n=struct.unpack(bo+'H',f[ifd:ifd+2])[0]
tags={}
for i in range(n):
    e=ifd+2+i*12
    tag,typ,cnt=struct.unpack(bo+'HHI',f[e:e+8])
    size={3:2,4:4,12:8}.get(typ,1)
    pos=e+8 if size*cnt<=4 else struct.unpack(bo+'I',f[e+8:e+12])[0]
    fmt={3:'H',4:'I',12:'d'}.get(typ)
    if fmt: tags[tag]=struct.unpack(bo+fmt*cnt,f[pos:pos+size*cnt])
w=tags[256][0];h=tags[257][0];offs=tags[273];lens=tags[279]
print('w',w,'h',h,'tiepoint',tags.get(33922),'scale',tags.get(33550),'pred',tags.get(317))
def lzw(inp,outlen):
    prefix=[-1]*4096;suffix=[0]*4096;length=[0]*4096
    for i in range(256): suffix[i]=i;length[i]=1
    out=bytearray();pos=0;bitbuf=0;bitcnt=0;codelen=9;nxt=258;old=-1
    def read():
        nonlocal pos,bitbuf,bitcnt
        while bitcnt<codelen:
            if pos>=len(inp): return 257
            bitbuf=(bitbuf<<8)|inp[pos];pos+=1;bitcnt+=8
        c=(bitbuf>>(bitcnt-codelen))&((1<<codelen)-1);bitcnt-=codelen;bitbuf&=(1<<bitcnt)-1;return c
    def s(code):
        r=bytearray(length[code]);i=length[code]-1;c=code
        while i>=0: r[i]=suffix[c];c=prefix[c];i-=1
        return r
    while len(out)<outlen:
        code=read()
        if code==257: break
        if code==256:
            codelen=9;nxt=258;code=read()
            if code==257: break
            out+=s(code);old=code;continue
        if old<0: out+=s(code);old=code;continue
        if code<nxt: st=s(code);out+=st;first=st[0]
        else: st=s(old);first=st[0];out+=st;out.append(first)
        if nxt<4096:
            prefix[nxt]=old;suffix[nxt]=first;length[nxt]=length[old]+1;nxt+=1
            if nxt==(1<<codelen)-1 and codelen<12: codelen+=1
        old=code
    return bytes(out[:outlen])
rps=h//len(offs)
raw=b''.join(lzw(f[o:o+l],rps*w*4) for o,l in zip(offs,lens))
mine=np.frombuffer(raw,dtype='<f4').reshape(h,w)
ref=np.fromfile('ref.raw',dtype='<f4').reshape(h,w)
print('LZW equal:',np.array_equal(mine,ref), 'maxdiff', np.nanmax(np.abs(mine-ref)))
# TM check
a=6378137.0;fl=1/298.257223563;e2=fl*(2-fl);ep2=e2/(1-e2);e4=e2*e2;e6=e4*e2
lat0=math.radians(42);lon0=math.radians(12.5)
def m(p): return a*((1-e2/4-3*e4/64-5*e6/256)*p-(3*e2/8+3*e4/32+45*e6/1024)*math.sin(2*p)+(15*e4/256+45*e6/1024)*math.sin(4*p)-(35*e6/3072)*math.sin(6*p))
m0=m(lat0)
def tm(lat,lon):
    phi=math.radians(lat);sp,cp,tp=math.sin(phi),math.cos(phi),math.tan(phi)
    nn=a/math.sqrt(1-e2*sp*sp);t=tp*tp;c=ep2*cp*cp;A=(math.radians(lon)-lon0)*cp;a2=A*A
    x=nn*(A+(1-t+c)*a2*A/6+(5-18*t+t*t+72*c-58*ep2)*a2*a2*A/120)
    y=m(phi)-m0+nn*tp*(a2/2+(5-t+9*c+4*c*c)*a2*a2/24+(61-58*t+t*t+600*c-330*ep2)*a2**3/720)
    return x,y
for line in open('pts_out.txt'):
    pass
pts=[l.split() for l in open('pts_in.txt')]
outs=[l.split() for l in open('pts_out.txt')]
for (lo,la),o in zip(pts,outs):
    x,y=tm(float(la),float(lo));print(lo,la,'mine',round(x,1),round(y,1),'gdal',o[0],o[1])
