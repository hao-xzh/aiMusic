import { materialIcons } from "./material-icons";

export type IconName = keyof typeof materialIcons | "play" | "pause" | "previous" | "next" | "search" | "profile" | "list" | "close" | "heart" | "heartFilled" | "lyrics" | "playlistAdd" | "spark" | "order" | "gear" | "collapse";
const heart = "M12 20.3C12 20.3 3.5 13.4 3.5 8.3C3.5 5.7 5.6 4 7.9 4C9.6 4 11.2 5.1 12 6.5C12.8 5.1 14.4 4 16.1 4C18.4 4 20.5 5.7 20.5 8.3C20.5 13.4 12 20.3 12 20.3Z";
export function Icon({ name, size = 24 }: { name: IconName; size?: number }) {
  if (name in materialIcons) return <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">{materialIcons[name as keyof typeof materialIcons].map((d, i) => <path key={i} d={d} />)}</svg>;
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {name === "play" && <path d="M8 5.5v13l11-6.5z" fill="currentColor" strokeWidth="2.4" />}
    {name === "pause" && <g fill="currentColor" stroke="none"><rect x="6" y="4.5" width="4.2" height="15" rx="1.6" /><rect x="13.8" y="4.5" width="4.2" height="15" rx="1.6" /></g>}
    {name === "previous" && <path d="M13 7.2L7 11.5v1L13 16.8zM21 7.2l-6 4.3v1l6 4.3z" fill="currentColor" strokeWidth="2.2" />}
    {name === "next" && <path d="M3 7.2l6 4.3v1l-6 4.3zM11 7.2l6 4.3v1l-6 4.3z" fill="currentColor" strokeWidth="2.2" />}
    {name === "search" && <><circle cx="11" cy="11" r="7" /><path d="M16.2 16.2L20.5 20.5" /></>}
    {name === "profile" && <><circle cx="12" cy="8" r="4" /><path d="M4 21a8 8 0 0 1 16 0" /></>}
    {name === "list" && <><path d="M3.5 6h11M3.5 12h11M3.5 18h7M19.5 4v9" strokeWidth="2.2" /><ellipse cx="17.7" cy="14" rx="2.2" ry="1.6" fill="currentColor" stroke="none" /></>}
    {name === "close" && <path d="M6 6L18 18M18 6L6 18" strokeWidth="2.4" />}
    {(name === "heart" || name === "heartFilled") && <path d={heart} fill={name === "heartFilled" ? "currentColor" : "none"} strokeWidth={name === "heartFilled" ? 1.6 : 2} />}
    {name === "lyrics" && <g strokeWidth="2.2"><rect x="3" y="5" width="18" height="14" rx="2.5" /><path d="M7 10.5h6.5M7 14.5h9" /></g>}
    {name === "playlistAdd" && <path d="M12 5.5v13M5.5 12h13" strokeWidth="2.4" />}
    {name === "spark" && <><path d="M12 3v6M12 15v6M3 12h6M15 12h6M5.5 5.5l3 3M15.5 15.5l3 3M5.5 18.5l3-3M15.5 8.5l3-3" /><circle cx="12" cy="12" r="1" fill="currentColor" stroke="none" /></>}
    {name === "order" && <path d="M5 6.5h9.4M5 12h8M5 17.5h6.2M17.8 5.4v13.2M14.8 15.8l3 2.8 3-2.8" />}
    {name === "collapse" && <path d="M4.32 8.64L12 15.84L19.68 8.64" strokeWidth={1.46} />}
    {name === "gear" && <g strokeWidth="2.2"><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06 .06a2 2 0 0 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33a1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.76 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.34 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1Z" /></g>}
  </svg>;
}
