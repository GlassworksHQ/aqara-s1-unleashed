// The recipes import shell scripts as text (`./x.sh?raw`); next.config.ts gives Turbopack the rule.
declare module '*?raw' {
  const text: string;
  export default text;
}
