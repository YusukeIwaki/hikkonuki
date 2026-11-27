# Hikkonuki website

The product site, built with [Astro](https://astro.build). English is at the root and Japanese is under `/ja/`.

```sh
npm install
npm run dev      # http://localhost:4321
npm run build    # output in dist/
```

- `src/pages/*.astro` are the English pages, `src/pages/ja/*.astro` the Japanese ones. Each page exists in both.
- `src/layouts/Layout.astro` holds the header, the language switch and the shared styles.
- `public/favicon.svg` is `design/app-icon.svg` with the artwork enlarged: the launcher icon leaves room for Android to crop it, a web icon does not need to. `public/app-icon-512.png` is rendered from it with `rsvg-convert -w 512 -h 512`.
- `public/shots/{en,ja}/` are screenshots taken by `ScreenCatalogTest`, with a neutral camera name and file names so that no product names appear on the site. The photos in them are stock photos listed in `CREDITS.md`.
