"use client";

import type { MouseEvent, ReactNode } from "react";

type SmartLinkProps = {
  href: string;
  className?: string;
  children: ReactNode;
  prefetch?: boolean;
  title?: string;
  target?: string;
  rel?: string;
  onClick?: (event: MouseEvent<HTMLAnchorElement>) => void;
};

export function SmartLink({
  href,
  className = "",
  children,
  title,
  target,
  rel,
  onClick,
}: SmartLinkProps) {
  const mergedClassName = `touch-manipulation ${className}`.trim();

  // Data-heavy dynamic pages are fast on the home server, while a client-side
  // router transition can remain pending when hydration or an RSC request is
  // interrupted. A native anchor keeps navigation functional before/without
  // hydration and makes every card, pagination control and header link share
  // the browser's deterministic fallback behavior.
  return (
    <a
      href={href}
      title={title}
      target={target}
      rel={rel}
      onClick={onClick}
      className={mergedClassName}
    >
      {children}
    </a>
  );
}
