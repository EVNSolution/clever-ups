import { render } from "preact";
import { App } from "./App";
import "./styles/tokens.css";
import "./styles/base.css";
import "./styles/layout.css";
import "./styles/components.css";

const root = document.getElementById("app");
if (root === null) throw new Error("Missing #app root element");
render(<App />, root);
