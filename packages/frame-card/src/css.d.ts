// CSS modules, as Next.js and Vite load them.
declare module '*.module.css' {
  const classes: Record<string, string>;
  export default classes;
}
