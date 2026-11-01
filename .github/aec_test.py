import struct, subprocess, sys
fn=sys.argv[1]
b=open(fn,'rb').read()
def u(p,n): return int.from_bytes(b[p:p+n],'big')
def smag(v,n):
    s=1<<(8*n-1); return -(v&(s-1)) if v&s else v
pos=16; sec={}
while pos+5<=len(b):
    if b[pos:pos+4]==b'7777': break
    ln=u(pos,4); sec[b[pos+4]]=(pos,ln); pos+=ln
p5,_=sec[5]
N=u(p5+5,4); tmpl=u(p5+9,2)
R=struct.unpack('>f',b[p5+11:p5+15])[0]; E=smag(u(p5+15,2),2); D=smag(u(p5+17,2),2); nb=b[p5+19]
flags=b[p5+21]; J=b[p5+22]; rsi=u(p5+23,2)
print('tmpl',tmpl,'N',N,'nbits',nb,'flags',flags,'block',J,'rsi',rsi,'R',R,'E',E,'D',D)
p7,l7=sec[7]; data=b[p7+5:p7+l7]
bitpos=0
def rb(n):
    global bitpos
    v=0
    for _ in range(n):
        v=(v<<1)|((data[bitpos>>3]>>(7-(bitpos&7)))&1); bitpos+=1
    return v
def fs():
    c=0
    while rb(1)==0: c+=1
    return c
pp=bool(flags&8); padrsi=bool(flags&32); restricted=bool(flags&16)
idlen = 1 if (restricted and nb<=2) else (2 if (restricted and nb<=4) else (3 if nb<=8 else (4 if nb<=16 else 5)))
xmax=(1<<nb)-1
out=[]
def emit_mapped(vals, ref_present):
    pass
total=N
while len(out)<total:
    # one RSI
    rsi_vals=[]
    blocks_left=rsi
    first=True
    blocks_in_seg=0
    while blocks_left>0 and len(out)+len(rsi_vals)<total:
        ref = pp and first
        idv=rb(idlen)
        blk=[]
        if idv==0:
            bit=rb(1)
            if ref: blk.append(('ref',rb(nb)))
            if bit==0:
                z=fs()
                if z==4:  # ROS
                    nz=min(blocks_left, 64 - (( rsi-blocks_left) % 64))
                elif z<4: nz=z+1
                else: nz=z
                cnt=nz*J - (1 if ref else 0)
                blk+= [0]*cnt
                blocks_left-=nz; first=False
                rsi_vals+=blk; continue
            else:
                i=1 if ref else 0
                while i<J:
                    m=fs()
                    beta=0
                    while (beta+1)*(beta+2)//2 <= m: beta+=1
                    x1=m-beta*(beta+1)//2; x0=beta-x1
                    if i%2==0: blk.append(x0); i+=1
                    blk.append(x1); i+=1
        elif idv==(1<<idlen)-1:
            if ref: blk.append(('ref',rb(nb)))
            for _ in range(J-(1 if ref else 0)): blk.append(rb(nb))
        else:
            k=idv-1
            if ref: blk.append(('ref',rb(nb)))
            n=J-(1 if ref else 0)
            hi=[fs() for _ in range(n)]
            vals=[(h<<k)|rb(k) if k>0 else h for h in hi]
            blk+=vals
        rsi_vals+=blk; blocks_left-=1; first=False
    # inverse preprocessing
    prev=None
    for v in rsi_vals:
        if len(out)>=total: break
        if isinstance(v,tuple): x=v[1]
        elif not pp: x=v
        else:
            p=prev; theta=min(p, xmax-p)
            if v<=2*theta: x = p + (v//2 if v%2==0 else -((v+1)//2))
            else: x = v if theta==p else xmax - v
        out.append(x); prev=x
    if padrsi: bitpos=(bitpos+7)//8*8
vals=[(R+x*2**E)/10**D for x in out[:N]]
ref=subprocess.run(['grib_get_data',fn],capture_output=True,text=True).stdout.split('\n')[1:]
ok=bad=0
for k in range(0,N,max(1,N//300)):
    p=ref[k].split()
    if len(p)<3: continue
    v=float(p[2])
    if abs(vals[k]-v)<=1e-3*max(1,abs(v)): ok+=1
    else:
        bad+=1
        if bad<5: print('diff k',k,'eccodes',v,'mine',vals[k])
print('ok',ok,'bad',bad,'decoded',len(out))
