/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        paper: '#f7f3ec',
        'paper-2': '#efe9df',
        surface: '#fffcf7',
        ink: '#16130e',
        'ink-2': '#3a342b',
        'ink-3': '#6e6659',
        line: '#e2dbce',
        'line-2': '#c8bfaf',
        accent: '#ffb020',
        'accent-strong': '#d9890f',
      },
      fontFamily: {
        sans: [
          'ui-sans-serif',
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'Roboto',
          'Helvetica Neue',
          'Arial',
          'sans-serif',
        ],
        mono: [
          'ui-monospace',
          'SFMono-Regular',
          'Menlo',
          'Consolas',
          'Liberation Mono',
          'monospace',
        ],
      },
    },
  },
  plugins: [],
}
