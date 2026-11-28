import { mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "playwright";

const output = new URL("../public/og/", import.meta.url);
await mkdir(output, { recursive: true });

const browser = await chromium.launch();
try {
  const page = await browser.newPage({
    viewport: { width: 1200, height: 630 },
    deviceScaleFactor: 1,
  });

  for (const lang of ["en", "ja"]) {
    const source = new URL("card.html", import.meta.url);
    source.searchParams.set("lang", lang);
    await page.goto(source.href);
    await page.evaluate(async () => {
      await document.fonts.ready;
      await Promise.all(Array.from(document.images, (image) => image.decode()));
    });
    await page.locator(".card").screenshot({
      path: fileURLToPath(new URL(`${lang}.png`, output)),
      animations: "disabled",
    });
  }
} finally {
  await browser.close();
}
