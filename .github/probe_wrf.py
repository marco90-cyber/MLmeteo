import struct, subprocess, math, sys
b=open('x.grib','rb').read()
def s_mag(v,nbytes):
    sign=1<<(8*nbytes-1)
    return -(v&(sign-1)) if v&sign else v
def u(pos,n): return int.from_bytes(b[pos:pos+n],'big')
pos=16; sec={}
while pos+5<=len(b):
    if b[pos:pos+4]==b'7777': break
    ln=u(pos,4); sec[b[pos+4]]=(pos,ln); pos+=ln
p3,_=sec[3]; tmpl3=u(p3+12,2); print('grid tmpl',tmpl3)
# template 3.30 Lambert (offsets 1-based octets -> pos+oct-1)
o=lambda k:p3+k-1
shape=b[o(15)]; nx=u(o(31),4); ny=u(o(35),4)
la1=s_mag(u(o(39),4),4)/1e6; lo1=u(o(43),4)/1e6; resf=b[o(47)]
lad=s_mag(u(o(48),4),4)/1e6; lov=u(o(52),4)/1e6; dx=u(o(56),4)/1e3; dy=u(o(60),4)/1e3
pcf=b[o(64)]; scan=b[o(65)]; latin1=s_mag(u(o(66),4),4)/1e6; latin2=s_mag(u(o(70),4),4)/1e6
print('shape',shape,'nx',nx,'ny',ny,'la1',la1,'lo1',lo1,'lad',lad,'lov',lov,'dx',dx,'dy',dy,'scan',scan,'latin',latin1,latin2,'radius?',u(o(16),1),u(o(17),4))
p5,_=sec[5]; t5=u(p5+9,2); print('pack tmpl',t5)
R=struct.unpack('>f',b[p5+11:p5+15])[0]; E=s_mag(u(p5+15,2),2); D=s_mag(u(p5+17,2),2); nb=b[p5+19]
N=u(p5+5,4)
print('N',N,'R',R,'E',E,'D',D,'nbits',nb)
if t5==3:
    q=lambda k:p5+k-1
    split=b[q(22)]; miss=b[q(23)]; NG=u(q(32),4); wref=b[q(36)]; wbits=b[q(37)]; lref=u(q(38),4); linc=b[q(42)]; llast=u(q(43),4); lbits=b[q(47)]; order=b[q(48)]; nyb=b[q(49)]
    print('miss',miss,'NG',NG,'wref',wref,'wbits',wbits,'lref',lref,'linc',linc,'llast',llast,'lbits',lbits,'order',order,'nyb',nyb)
    p7,l7=sec[7]; data=b[p7+5:p7+l7]
    bitpos=0
    def rb(n):
        global bitpos
        v=0
        for _ in range(n):
            v=(v<<1)|((data[bitpos>>3]>>(7-(bitpos&7)))&1); bitpos+=1
        return v
    def align():
        global bitpos
        bitpos=(bitpos+7)//8*8
    ival1=s_mag(rb(8*nyb),nyb); ival2=s_mag(rb(8*nyb),nyb) if order==2 else 0; minsd=s_mag(rb(8*nyb),nyb)
    refs=[rb(nb) for _ in range(NG)]; align()
    widths=[rb(wbits)+wref for _ in range(NG)]; align()
    lens=[rb(lbits)*linc+lref for _ in range(NG)]; align(); lens[-1]=llast
    print('sum lens',sum(lens))
    X=[];MISS=None
    for g in range(NG):
        w=widths[g]
        for k in range(lens[g]):
            v=rb(w) if w>0 else 0
            if miss==1 and ((w>0 and v==(1<<w)-1) or (w==0 and refs[g]==(1<<nb)-1)): X.append(None)
            else: X.append(refs[g]+v)
    # spatial differencing on non-missing values
    idx=[i for i,v in enumerate(X) if v is not None]
    vals=[X[i] for i in idx]
    if order==1:
        vals[0]=ival1
        for i in range(1,len(vals)): vals[i]=vals[i]+minsd+vals[i-1]
    else:
        vals[0]=ival1; vals[1]=ival2
        for i in range(2,len(vals)): vals[i]=vals[i]+minsd+2*vals[i-1]-vals[i-2]
    out=[None]*len(X)
    for i,v in zip(idx,vals): out[i]=(R+v*2**E)/10**D
else:
    p7,l7=sec[7]; out=None
# lambert forward (sphere)
Rr=6371229.0
def lam(lat,lon):
    phi1=math.radians(latin1);phi2=math.radians(latin2);phi0=math.radians(lad);l0=math.radians(lov)
    n=math.log(math.cos(phi1)/math.cos(phi2))/math.log(math.tan(math.pi/4+phi2/2)/math.tan(math.pi/4+phi1/2)) if abs(latin1-latin2)>1e-6 else math.sin(phi1)
    F=math.cos(phi1)*math.tan(math.pi/4+phi1/2)**n/n
    rho=lambda p: Rr*F/math.tan(math.pi/4+p/2)**n
    r=rho(math.radians(lat)); r0=rho(phi0); th=n*(math.radians(lon)-l0)
    th=n*((math.radians(lon)-l0+math.pi)%(2*math.pi)-math.pi)
    return r*math.sin(th), r0-r*math.cos(th)
x0,y0=lam(la1,lo1)
lines=subprocess.run(['grib_get_data','x.grib'],capture_output=True,text=True).stdout.split('\n')[1:]
ok=bad=0
for k in range(0,nx*ny,nx*97+13):
    p=lines[k].split()
    if len(p)<3: continue
    lat,lon,v=map(float,p)
    x,y=lam(lat,lon); i=round((x-x0)/(dx)); j=round((y-y0)/(dy))
    m=out[j*nx+i] if out else None
    good=(m is not None and abs(m-v)<1e-3*max(1,abs(v)) and j*nx+i==k)
    ok+=good; bad+= (not good)
    if not good and bad<6: print('k',k,'ij',i,j,'expected idx',k%nx,k//nx,'v',v,'mine',m)
print('ok',ok,'bad',bad)
