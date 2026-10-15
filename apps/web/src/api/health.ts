import { ApiError, getJson } from "./http";
import type { paths } from "./schema";

export type Health = paths["/actuator/health"]["get"]["responses"]["200"]["content"]["application/json"];

export async function getHealth(): Promise<Health> {
  const { body } = await getJson("/actuator/health");
  if (typeof body !== "object" || body === null || !("status" in body) || body.status !== "UP") {
    throw new ApiError("shape", "상태 확인 응답이 올바르지 않습니다.");
  }
  return { status: body.status };
}
