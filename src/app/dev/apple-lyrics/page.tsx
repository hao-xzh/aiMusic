import { notFound } from "next/navigation";

export default async function AppleLyricsDevPage() {
  if (process.env.NODE_ENV !== "production") {
    const { default: DevOnlyLyricsScene } = await import("@/app/dev/apple-lyrics/AppleLyricsVerificationScene");
    return <DevOnlyLyricsScene />;
  }

  notFound();
}
