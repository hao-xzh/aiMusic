import type { NativeTrack } from "./model";

const religious=["大悲咒","佛教","佛经","佛乐","佛音","念佛","诵经","心经","六字大明咒","南无","阿弥陀佛","观音心咒","宗教音乐","赞美诗","圣歌","buddhist","buddhism","sutra","mantra","gregorian chant","worship music","gospel"];
const functional=["纯音乐","轻音乐","催眠","助眠","入睡","安眠","哄睡","睡眠音乐","冥想","白噪音","白噪声","胎教","疗愈","理疗","养生","八音盒","脑波","脑部","spa","安神","静心","瑜伽","禅修","钢琴曲集","写代码","编程","专注音乐","工作音乐","学习音乐","meditation","white noise","sleep music","relaxing music","study music","healing music","bgm合集","coding","programming music","deep focus","focus music","concentration","work music","study beats","lofi beats","lo-fi beats","calm code"];
const negation=/不要|不想|不喜欢|别放|别来|不听|排除|避开|讨厌|不含|拒绝|without|don't|do not|avoid|no /i;
const categoryKey=(value:string) => value.toLowerCase().replace(/[\s\p{P}\p{S}]+/gu,"");
function positiveMention(text:string,words:string[]) {return text.toLowerCase().split(/[，。；！？,;.!?\n]/).some(clause => words.some(word => {const index=clause.indexOf(word);return index>=0 && !negation.test(clause.slice(0,index));}));}
export function isReligious(track:NativeTrack|string) {const text=categoryKey(typeof track==="string"?track:`${track.title} ${track.artist} ${track.album}`);return religious.some(word => text.includes(categoryKey(word)));}
export function isFunctional(track:NativeTrack) {const text=`${track.title} ${track.artist}`.toLowerCase();return functional.some(word => text.includes(word));}
export const permitsFunctional=(text:string) => positiveMention(text,[...functional,...religious]);
export const acceptsCategory=(track:NativeTrack,text:string) => !isReligious(track) || positiveMention(text,religious);
