import { discovery } from "./dist/routing.js";

async function main() {
  const instances = await discovery.getInstances();
  console.log("Discovered Instances:", JSON.stringify(instances, null, 2));
}

main().catch(console.error);
