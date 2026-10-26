"use client";

import { useEffect,useId,useRef,type RefObject } from "react";
import { bezier,clamp01,smooth } from "@/lib/pwa/motion";
import { diagnostic } from "@/lib/pwa/diagnostics";
import { assistantLightShader } from "./assistant-shader";

type WakeGpu={gl:WebGLRenderingContext;program:WebGLProgram;buffer:WebGLBuffer;shaders:WebGLShader[];resolution:WebGLUniformLocation|null;progress:WebGLUniformLocation|null;intensity:WebGLUniformLocation|null};

// SourceGraphic is the live page. The normal map contains only analytic wave
// vectors from AssistantWaterShader; no DOM screenshot or page bitmap is used.
export function AssistantWake({open,target:content}:{open:boolean;target:RefObject<HTMLDivElement|null>}) {
  const id=useId().replace(/:/g,""),canvas=useRef<HTMLCanvasElement>(null),filter=useRef<SVGFilterElement>(null),mapImage=useRef<SVGFEImageElement>(null),displacement=useRef<SVGFEDisplacementMapElement>(null);
  const phase=useRef(0),strength=useRef(0);
  const gpu=useRef<WakeGpu|null>(null);
  useEffect(() => {
    const element=canvas.current;
    const lost=(event:Event) => {event.preventDefault();gpu.current=null;};
    element?.addEventListener("webglcontextlost",lost);
    return () => {element?.removeEventListener("webglcontextlost",lost);const resources=gpu.current;if(!resources) return;const {gl,program,buffer,shaders}=resources;gl.deleteBuffer(buffer);gl.deleteProgram(program);shaders.forEach(shader => gl.deleteShader(shader));gpu.current=null;};
  },[]);
  useEffect(() => {
    const element=canvas.current,host=content.current;if(!element || !host) return;
    if(!open && strength.current<=0) return;
    const reduced=matchMedia("(prefers-reduced-motion: reduce)").matches;
    if(reduced) {host.style.removeProperty("filter");return;}
    if(!gpu.current) {
      const gl=element.getContext("webgl",{alpha:true,premultipliedAlpha:true,antialias:false,depth:false,preserveDrawingBuffer:false});
      let program:WebGLProgram|null=null,buffer:WebGLBuffer|null=null;const shaders:WebGLShader[]=[];
      const compile=(kind:number,source:string) => {if(!gl) return null;const shader=gl.createShader(kind);if(!shader) return null;gl.shaderSource(shader,source);gl.compileShader(shader);if(!gl.getShaderParameter(shader,gl.COMPILE_STATUS)) {gl.deleteShader(shader);return null;}shaders.push(shader);return shader;};
      if(gl) {
        const vertex=compile(gl.VERTEX_SHADER,"attribute vec2 position;void main(){gl_Position=vec4(position,0.0,1.0);}"),fragment=compile(gl.FRAGMENT_SHADER,assistantLightShader);
        if(vertex && fragment) {program=gl.createProgram();if(program) {gl.attachShader(program,vertex);gl.attachShader(program,fragment);gl.linkProgram(program);if(!gl.getProgramParameter(program,gl.LINK_STATUS)) {gl.deleteProgram(program);program=null;}}}
        if(program) {
          gl.useProgram(program);buffer=gl.createBuffer();
          if(buffer) {gl.bindBuffer(gl.ARRAY_BUFFER,buffer);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);const position=gl.getAttribLocation(program,"position");gl.enableVertexAttribArray(position);gl.vertexAttribPointer(position,2,gl.FLOAT,false,0,0);
            gpu.current={gl,program,buffer,shaders,resolution:gl.getUniformLocation(program,"resolution"),progress:gl.getUniformLocation(program,"progress"),intensity:gl.getUniformLocation(program,"intensity")};}
        }
        if(!gpu.current) {if(program) gl.deleteProgram(program);shaders.forEach(shader => gl.deleteShader(shader));}
      }
      if(!gpu.current) diagnostic("assistant","wake_light_unavailable");
    }
    const resize=() => {
      const width=Math.max(1,host.clientWidth),height=Math.max(1,host.clientHeight),ratio=Math.min(devicePixelRatio,1.5);
      element.width=Math.round(width*ratio);element.height=Math.round(height*ratio);
      filter.current?.setAttribute("width",String(width));filter.current?.setAttribute("height",String(height));mapImage.current?.setAttribute("width",String(width));mapImage.current?.setAttribute("height",String(height));
      const resources=gpu.current;if(resources) {resources.gl.viewport(0,0,element.width,element.height);resources.gl.uniform2f(resources.resolution,element.width,element.height);}
    };
    resize();const observer=new ResizeObserver(resize);observer.observe(host);
    const normal=document.createElement("canvas");normal.width=64;normal.height=128;const context=normal.getContext("2d"),pixels=context?.createImageData(normal.width,normal.height);
    const start=performance.now(),initial=strength.current,ease=bezier(.4,0,.2,1);let frame=0,lastMap=0;
    const bends=new Float32Array(normal.width),slopes=new Float32Array(normal.width),modulations=new Float32Array(normal.width),modulationSlopes=new Float32Array(normal.width);
    if(open) phase.current=0;
    const tick=(now:number) => {
      const elapsed=now-start;if(document.visibilityState!=="visible") {strength.current=0;host.style.removeProperty("filter");element.style.opacity="0";return;}
      if(open) {phase.current=.68*Math.min(elapsed,1550)/1800;strength.current=elapsed<650?initial+(1-initial)*ease(clamp01(elapsed/420)):1-ease(clamp01((elapsed-650)/900));}
      else strength.current=initial*(1-ease(clamp01(elapsed/900)));
      const intensity=strength.current,progress=phase.current,fade=smooth(progress,0,.08)*intensity;
      if(intensity>.001) {
        element.style.opacity="1";
        const resources=gpu.current;if(resources) {const {gl}=resources;gl.uniform1f(resources.progress,progress);gl.uniform1f(resources.intensity,intensity);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);}
        if(context && pixels && now-lastMap>=1000/30) {
          lastMap=now;
          for(let x=0;x<normal.width;x++) {const ux=x/(normal.width-1),a=ux*8+progress*3,b=ux*15-progress*2,mod=ux*5-progress*4;bends[x]=.045*Math.sin(a)+.022*Math.sin(b);slopes[x]=.36*Math.cos(a)+.33*Math.cos(b);modulations[x]=.45*Math.sin(mod);modulationSlopes[x]=2.25*Math.cos(mod);}
          for(let y=0;y<normal.height;y++) for(let x=0;x<normal.width;x++) {const uy=y/(normal.height-1),slope=slopes[x],u=1-uy-progress*1.45+.10+bends[x],p=u*19+modulations[x],env=Math.exp(-u*u*28),wave=Math.sin(p),crest=Math.cos(p),dx=env*(crest*(19*slope+modulationSlopes[x])-56*u*slope*wave),dy=env*(-19*crest+56*u*wave),nx=-dx*.05,ny=-dy*.045,length=Math.hypot(nx,ny,1),offset=(y*normal.width+x)*4;pixels.data[offset]=Math.round((nx/length*.5+.5)*255);pixels.data[offset+1]=Math.round((ny/length*.5+.5)*255);pixels.data[offset+2]=128;pixels.data[offset+3]=255;}
          context.putImageData(pixels,0,0);mapImage.current?.setAttribute("href",normal.toDataURL());host.style.filter=`url(#${id})`;
        }
        displacement.current?.setAttribute("scale",String(16*fade));
      } else {host.style.removeProperty("filter");element.style.opacity="0";}
      // The first frame may be almost zero; the timeline, not its current opacity, owns completion.
      if(elapsed<(open?1550:900)) frame=requestAnimationFrame(tick);
      else {strength.current=0;host.style.removeProperty("filter");element.style.opacity="0";}
    };frame=requestAnimationFrame(tick);
    return () => {cancelAnimationFrame(frame);observer.disconnect();host.style.removeProperty("filter");element.style.opacity="0";};
  },[open,content,id]);
  return <><svg className="pipo-wake-filter" aria-hidden="true"><defs><filter ref={filter} id={id} x="0" y="0" width="1" height="1" filterUnits="userSpaceOnUse" primitiveUnits="userSpaceOnUse" colorInterpolationFilters="sRGB"><feImage ref={mapImage} result="wave" x="0" y="0" width="1" height="1" preserveAspectRatio="none"/><feDisplacementMap ref={displacement} in="SourceGraphic" in2="wave" scale="0" xChannelSelector="R" yChannelSelector="G" /></filter></defs></svg><canvas ref={canvas} className="pipo-wake-light" aria-hidden="true" /></>;
}
