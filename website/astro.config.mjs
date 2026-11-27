// @ts-check
import { defineConfig } from 'astro/config';

// English lives at the root, Japanese under /ja/.
// https://astro.build/config
export default defineConfig({
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'ja'],
    routing: { prefixDefaultLocale: false },
  },
});
