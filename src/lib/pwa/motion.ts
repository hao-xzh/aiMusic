// Unit-mass spring with retained velocity, matching Compose's dampingRatio/stiffness contract.
export class Spring {
  value:number; velocity=0; target:number; stiffness:number; damping:number;
  constructor(value=0,stiffness=300,damping=.94) { this.value=value; this.target=value; this.stiffness=stiffness; this.damping=damping; }
  to(target:number,stiffness=this.stiffness,damping=this.damping) { this.target=target; this.stiffness=stiffness; this.damping=damping; }
  snap(value:number) { this.value=value; this.target=value; this.velocity=0; }
  step(seconds:number) {
    const dt=Math.min(.064,Math.max(0,seconds)),w=Math.sqrt(this.stiffness),z=this.damping,x=this.value-this.target,v=this.velocity;
    if (z<1) {
      const wd=w*Math.sqrt(1-z*z),decay=Math.exp(-z*w*dt),cos=Math.cos(wd*dt),sin=Math.sin(wd*dt),b=(v+z*w*x)/wd;
      this.value=this.target+decay*(x*cos+b*sin);
      this.velocity=decay*((-z*w*x+wd*b)*cos+(-z*w*b-wd*x)*sin);
    } else {
      const decay=Math.exp(-w*dt),b=v+w*x; this.value=this.target+(x+b*dt)*decay; this.velocity=(v-w*b*dt)*decay;
    }
    if (Math.abs(this.value-this.target)<.001 && Math.abs(this.velocity)<.001) this.snap(this.target);
    return this.value;
  }
  get settled() { return this.value===this.target && this.velocity===0; }
}
export const clamp01=(value:number) => Math.max(0,Math.min(1,value));
export const smooth=(value:number,start=0,end=1) => { const x=clamp01((value-start)/(end-start)); return x*x*(3-2*x); };
export function bezier(x1:number,y1:number,x2:number,y2:number) {
  return (value:number) => {
    const x=clamp01(value); let t=x;
    const at=(a:number,b:number,t:number) => 3*a*(1-t)*(1-t)*t+3*b*(1-t)*t*t+t*t*t;
    for(let i=0;i<6;i++) { const derivative=3*x1*(1-t)*(1-t)+6*(x2-x1)*(1-t)*t+3*(1-x2)*t*t; if (Math.abs(derivative)<.00001) break; t=clamp01(t-(at(x1,x2,t)-x)/derivative); }
    return at(y1,y2,t);
  };
}
export const flipEase=bezier(.32,.72,0,1);
