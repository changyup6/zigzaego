import type { ApiResponse } from "@/lib/types";

export const ok = <T>(data: T): ApiResponse<T> => ({ success: true, data, error: null });
