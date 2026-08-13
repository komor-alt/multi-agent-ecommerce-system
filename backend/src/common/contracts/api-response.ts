import { ErrorCode } from "../errors/error-codes";

export type ApiSuccessResponse<T> = {
  success: true;
  data: T;
  requestId: string;
};

export type ApiErrorResponse = {
  success: false;
  error: {
    code: ErrorCode;
    message: string;
    details?: Record<string, unknown>;
  };
  requestId: string;
};

export type ApiResponse<T> = ApiSuccessResponse<T> | ApiErrorResponse;
