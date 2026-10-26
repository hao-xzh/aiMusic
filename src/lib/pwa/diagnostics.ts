import { readPreference,writePreference } from "./storage";
type Diagnostic={at:string;area:string;event:string;fields?:Record<string,string|number|boolean>};
let rows:Diagnostic[]|undefined;
export function diagnostic(area:string,event:string,fields?:Diagnostic["fields"]) {
  rows ||= readPreference<Diagnostic[]>("diagnostics",[]);rows.push({at:new Date().toISOString(),area,event,fields});if(rows.length>1600) rows=rows.slice(-1200);writePreference("diagnostics",rows);
}
export function diagnosticText() {return [`PIPO PWA diagnostics`,new Date().toISOString(),navigator.userAgent,`online=${navigator.onLine}; standalone=${matchMedia("(display-mode: standalone)").matches}`,...(rows || readPreference<Diagnostic[]>("diagnostics",[])).map(row => JSON.stringify(row))].join("\n");}
