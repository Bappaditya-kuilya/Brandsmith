/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        /* Chippytea brand tokens (spec-defined) */
        paper: '#faf5ea',
        'paper-2': '#efe9da',
        surface: '#fffdf8',
        ink: '#33302b',
        'ink-2': '#4f4a43',
        'ink-3': '#6f685e',
        line: '#e5e7eb',
        'line-2': '#d1d5db',
        accent: '#f3b338',
        'accent-strong': '#d99a14',
        /* Semantic ramps derived from Chippytea anchors (error/success/warning) */
        red: {
          50: '#fbf0ee',
          100: '#f6e2de',
          200: '#e3b9b3',
          300: '#d9938a',
          400: '#c55b4f',
          500: '#c55b4f',
          600: '#b35044',
          700: '#a3453b',
          800: '#7f362e',
        },
        amber: {
          50: '#fdf4e3',
          100: '#fae8c4',
          200: '#f5d9a0',
          300: '#f0c46b',
          500: '#f3b338',
          700: '#8f6410',
          800: '#74510c',
          900: '#5a3f08',
        },
        emerald: {
          50: '#f4f6ec',
          100: '#e9eedb',
          300: '#b9c48f',
          500: '#7d8f4a',
          700: '#556132',
          800: '#455028',
          900: '#37401f',
        },
        green: {
          100: '#e9eedb',
          800: '#455028',
        },
      },
      fontFamily: {
        sans: [
          'ui-rounded',
          '-apple-system',
          'BlinkMacSystemFont',
          '"SF Pro Rounded"',
          '"Segoe UI"',
          'system-ui',
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
      borderRadius: {
        sm: '4px',
        md: '8px',
        lg: '12px',
        xl: '16px',
      },
    },
  },
  plugins: [],
}
