// @ts-check
import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

// English lives at the root, Japanese under /ja/.
// https://astro.build/config
export default defineConfig({
  site: 'https://yusukeiwaki.github.io',
  base: '/hikkonuki',
  trailingSlash: 'always',
  integrations: [sitemap()],
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'ja'],
    routing: { prefixDefaultLocale: false },
  },
});
