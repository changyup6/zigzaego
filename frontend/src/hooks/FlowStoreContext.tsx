"use client";
import { createContext, useContext } from "react";
import type { StoreMe } from "@/lib/types";

export const FlowStoreContext = createContext<StoreMe | null>(null);
export const useFlowStore = () => useContext(FlowStoreContext);
